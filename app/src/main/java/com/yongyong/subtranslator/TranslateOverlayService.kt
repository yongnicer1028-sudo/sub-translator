package com.yongyong.subtranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * 이 서비스 하나가 전부 다 해요:
 * 1. 영상 앱이 재생 중인 소리를 내부적으로 가져오고 (마이크 아님, 이어폰 껴도 됨)
 * 2. 몇 초 단위로 잘라서 Vosk(완전 무료, 오프라인 음성인식)로 글자를 만들고
 * 3. ML Kit(완전 무료, 오프라인)으로 한국어로 번역하고
 * 4. 화면 위에 떠 있는 작은 자막창에 표시해요.
 */
class TranslateOverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val NOTIF_CHANNEL_ID = "sub_translator_channel"
        private const val NOTIF_ID = 1001
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SECONDS = 3.5

        /** MainActivity가 화면에 "실행 중" 표시를 하기 위해 확인하는 값 */
        @Volatile
        var isRunning: Boolean = false
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var capturing = false
    private var busy = false

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var captionText: TextView? = null

    private var translator: Translator? = null
    private var speechClient: VoskSpeechClient? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 안드로이드 14 이상은 반드시 "포그라운드 서비스 시작"이 먼저이고,
        // 그 다음에 화면(소리) 캡처 권한을 사용해야 해요. 순서를 지켜야 크래시가 안 나요.
        startForegroundNotification()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val language = Prefs.getLanguage(this)
        val mlkitSourceLang = when (language) {
            Prefs.LANG_JAPANESE -> TranslateLanguage.JAPANESE
            Prefs.LANG_ENGLISH -> TranslateLanguage.ENGLISH
            else -> TranslateLanguage.CHINESE
        }

        showOverlay()
        updateCaptionSync("준비 중…")

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(mlkitSourceLang)
            .setTargetLanguage(TranslateLanguage.KOREAN)
            .build()
        val t = Translation.getClient(options)
        translator = t

        // 번역 모델(ML Kit) 준비 → 음성인식 모델(Vosk) 준비, 순서대로 진행합니다.
        // 둘 다 완전 무료고, 처음 한 번만 인터넷으로 받아두면 그 다음부터는 오프라인으로 동작해요.
        serviceScope.launch {
            try {
                updateCaption("번역 모델을 준비하는 중… (처음 한 번은 시간이 좀 걸려요)")
                t.downloadModelIfNeeded().await()
            } catch (e: Exception) {
                updateCaption("번역 모델을 받지 못했어요. 인터넷 연결을 확인하고 다시 시도해주세요.")
                return@launch
            }

            val client = VoskSpeechClient.load(applicationContext, language) { message ->
                updateCaptionSync(message)
            }
            if (client == null) {
                updateCaption("음성인식 모델을 준비하지 못했어요. 인터넷 연결을 확인하고 다시 시도해주세요.")
                return@launch
            }
            speechClient = client

            updateCaption("듣는 중…")
            // beginCapture 안에서 MediaProjection 콜백을 등록하는데, 이건 Looper가 있는
            // 스레드(메인 스레드)에서 해야 안전해서 여기서만 메인 스레드로 전환해요.
            withContext(Dispatchers.Main) {
                beginCapture(resultCode, resultData)
            }
        }

        return START_STICKY
    }

    private fun beginCapture(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            updateCaptionSync("소리 캡처 권한을 가져오지 못했어요.")
            return
        }
        mediaProjection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, null)

        startAudioCapture(projection)
        isRunning = true
    }

    private fun startForegroundNotification() {
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID, "실시간 번역", NotificationManager.IMPORTANCE_LOW
            )
            notifManager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("영상 소리를 번역하고 있어요")
            .setContentText("탭하면 앱으로 돌아가요 · 중지하려면 앱에서 '번역 중지'를 누르세요")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun showOverlay() {
        if (overlayView != null) return

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_caption, null)
        captionText = view.findViewById(R.id.textCaption)

        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 160
        }

        // 손가락으로 자막창을 드래그해서 원하는 위치로 옮길 수 있게
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(view, params)
            overlayView = view
        } catch (e: Exception) {
            // 오버레이 권한이 없는 등 예외 상황 - 알림으로만 상태를 알림
        }
    }

    private fun startAudioCapture(projection: MediaProjection) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = if (minBuf > 0) minBuf * 2 else SAMPLE_RATE * 2

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val record = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        } catch (e: Exception) {
            updateCaptionSync("오디오 캡처를 시작할 수 없어요: ${e.message}")
            return
        }

        audioRecord = record
        record.startRecording()
        capturing = true

        val bytesPerChunk = (SAMPLE_RATE * CHUNK_SECONDS * 2).toInt() // 16bit = 2byte
        val readBuf = ByteArray(4096)
        val outStream = ByteArrayOutputStream()

        serviceScope.launch {
            while (capturing) {
                val read = try {
                    record.read(readBuf, 0, readBuf.size)
                } catch (e: Exception) {
                    break
                }
                if (read > 0) {
                    outStream.write(readBuf, 0, read)
                }
                if (outStream.size() >= bytesPerChunk) {
                    val chunk = outStream.toByteArray()
                    outStream.reset()
                    processChunk(chunk)
                }
            }
        }
    }

    private fun processChunk(pcm: ByteArray) {
        val client = speechClient ?: return // 음성인식 모델이 아직 준비 안 됐으면 건너뜀
        if (busy) return // 이전 조각을 아직 처리 중이면 이번 조각은 건너뜀 (중복/역전 방지)
        if (isSilent(pcm)) return
        busy = true
        serviceScope.launch {
            try {
                val transcript = client.recognize(pcm)
                if (transcript.isNotBlank()) {
                    translateAndShow(transcript)
                }
            } finally {
                busy = false
            }
        }
    }

    private fun isSilent(pcm: ByteArray): Boolean {
        var sum = 0L
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            sum += abs(sample.toShort().toInt())
            i += 2
        }
        val sampleCount = pcm.size / 2
        if (sampleCount == 0) return true
        val avg = sum / sampleCount
        return avg < 150 // 이 값보다 작으면 사실상 무음으로 간주하고 건너뜀
    }

    private suspend fun translateAndShow(text: String) {
        val t = translator ?: return
        val translated = try {
            t.translate(text).await()
        } catch (e: Exception) {
            text
        }
        updateCaption(translated)
    }

    private suspend fun updateCaption(text: String) {
        withContext(Dispatchers.Main) {
            captionText?.text = text
        }
    }

    /** onStartCommand처럼 suspend가 아닌 곳에서 자막을 바로 바꾸고 싶을 때 */
    private fun updateCaptionSync(text: String) {
        captionText?.post { captionText?.text = text }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        capturing = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null

        mediaProjection?.stop()
        mediaProjection = null

        translator?.close()
        translator = null

        speechClient?.close()
        speechClient = null

        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlayView = null

        serviceJob.cancel()
        super.onDestroy()
    }
}
