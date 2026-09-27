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
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 이 서비스 하나가 전부 다 해요:
 * 1. 영상 앱이 재생 중인 소리를 내부적으로 가져오고 (마이크 아님, 이어폰 껴도 됨)
 * 2. Vosk(완전 무료, 오프라인 음성인식)에 소리를 끊김없이 계속 흘려보내면서, Vosk가
 *    "말이 한 번 끝났다"고 스스로 판단하는 순간마다 문장을 만들고
 * 3. M2M100(완전 무료, 온디바이스 번역 모델)으로 한국어로 번역하고
 * 4. 화면 위에 떠 있는 작은 자막창에 표시해요.
 *
 * ⚠ v2 변경점: 예전에는 소리를 3.5초 단위로 뚝뚝 잘라서 인식했더니 문장이 중간에
 * 잘리고(그래서 번역도 단어 몇 개 수준으로만 나왔어요), 게다가 번역하는 동안 새로
 * 들어온 소리는 통째로 버려지고 있었어요(그래서 계속 말을 해도 자막은 거의 안 뜸).
 * 지금은 (1) 소리를 끊지 않고 계속 흘려보내면서 Vosk가 스스로 "문장이 끝났다"를
 * 판단하게 하고, (2) 인식된 문장은 버리지 않고 순서대로 번역 대기열에 쌓아서
 * 하나도 놓치지 않게 바꿨어요.
 *
 * ⚠ v3 변경점: 자막창엔 번역된 한국어만 보여줘요(중간 인식 결과는 안 보여줘요), 최근
 * 5줄까지는 화면에 남아있게 했어요. 자막창 가장자리를 끌면 너비를 조절할 수 있고,
 * 더블탭하면 일시정지/재생, 길게 누르면 꺼져요(창이 사라져요).
 *
 * ⚠ v4 변경점: 번역 엔진을 ML Kit에서 온디바이스 모델(M2M100-418M)로 바꿨어요.
 * ML Kit 번역이 직역투로 어색하다는 문제가 있었는데, 별도 테스트 앱에서 검증해보니
 * 훨씬 자연스러웠어요. 처음엔 더 큰 NLLB-200-distilled-600M(약 900MB)로 했다가,
 * 음성인식(Vosk) 모델과 같이 메모리에 떠 있으면서 실제로 영상을 재생하면 메모리가
 * 부족해져 앱이 조용히 꺼지는 문제가 있어서, 이미 검증된 더 가벼운 M2M100-418M
 * (약 600MB)으로 바꿨어요. 최초 한 번만 번역 모델을 받으면 그 다음부터는 완전히
 * 오프라인/무료로 동작해요. (자세한 내용은 NllbTranslator.kt 참고)
 *
 * ⚠ v5 변경점: 번역 도중 메모리가 부족해지는 경우(OutOfMemoryError)는 일반적인
 * 오류(Exception)가 아니라서 예전 코드로는 못 잡고 앱 전체가 조용히 죽어버렸어요.
 * 이제는 그 문장 하나만 번역 실패로 처리하고, 앱과 자막창은 계속 살아있게 고쳤어요.
 *
 * ⚠ v6 변경점: 더블탭하면 곧바로 일시정지되던 걸, 이제는 [활성/비활성]·[닫기] 버튼
 * 2개가 뜨는 걸로 바꿨어요(창은 그대로 떠 있어요). 실제로 일시정지/재생은
 * [활성/비활성] 버튼을 눌러야 바뀌고, [닫기]를 눌러야 완전히 꺼져요(길게 누르기로
 * 끄던 방식은 없앴어요). 그리고 번역 품질 문제를 조사하려고 당분간 음성인식 원문도
 * 번역과 같이 보여줘요(SHOW_SOURCE_TEXT_FOR_DEBUG 참고).
 *
 * ⚠ v7 변경점: 자막창에 남겨두는 최근 번역 개수를 3개 → 10개로 늘렸고, 진단용
 * 원문/번역 표시를 "원문: …" / "번역: …" 두 줄로 더 알아보기 쉽게 바꿨어요.
 * 번역 속도는 NllbTranslator.kt에서 ONNX Runtime 최적화 설정을 올려서 개선했어요
 * (모델의 캐시(KV-cache) 지원 디코더로 완전히 바꾸는 더 큰 작업은, 그 모델 파일을
 * 미리 내려받아 구조를 확인해야 안전하게 만들 수 있는데 지금 이 작업 환경에서
 * huggingface.co 접속이 막혀 있어서 다음 기회로 미뤄뒀어요 — 태블릿이 실제로
 * 오작동하는 걸 막기 위한 안전한 선택이에요).
 */
class TranslateOverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val NOTIF_CHANNEL_ID = "sub_translator_channel"
        private const val NOTIF_ID = 1001
        private const val SAMPLE_RATE = 16000

        /** 이만큼 말이 안 끊기고 계속되면, 기다리지 않고 지금까지 들은 걸 강제로 한 문장으로 끊어요. */
        private const val MAX_UTTERANCE_MS = 8000L

        /** 자막창에 최근 번역을 몇 개까지 남겨둘지 */
        private const val MAX_CAPTION_LINES = 10

        // ⚠ 임시 진단용: 번역 품질이 너무 안 좋다는 문제를 조사하려고, 당분간
        // "음성인식이 실제로 알아들은 원문"도 번역 위에 같이 보여줘요. 이러면
        // 문제가 (1) 음성인식이 애초에 잘못 알아들은 건지 (2) 제대로 알아들었는데
        // 번역만 엉뚱하게 나오는 건지 구분할 수 있어요. 원인을 찾으면 false로
        // 바꿔서 번역 결과만 깔끔하게 보이게 되돌리면 돼요.
        private const val SHOW_SOURCE_TEXT_FOR_DEBUG = true

        /** MainActivity가 화면에 "실행 중" 표시를 하기 위해 확인하는 값 */
        @Volatile
        var isRunning: Boolean = false
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    /** Vosk가 문장을 완성해줄 때마다 여기 순서대로 쌓아두고, 따로 하나씩 번역해요. */
    private val translationQueue = Channel<String>(Channel.UNLIMITED)

    /** 최근 번역된 문장들 (화면엔 이 중 최신 몇 줄만 보여줘요) */
    private val captionLines = ArrayDeque<String>()

    /** 자막창을 더블탭하면 이 값이 바뀌어요. true면 소리를 들어도 인식/번역을 하지 않아요. */
    @Volatile
    private var paused = false

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var capturing = false

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var captionText: TextView? = null

    /** 더블탭하면 나타나는 [활성/비활성]·[닫기] 버튼 줄이에요. 평소엔 숨겨져 있어요. */
    private var controlBar: View? = null
    private var btnToggleActive: TextView? = null

    private var translator: NllbTranslator? = null
    private var speechClient: VoskSpeechClient? = null

    /** onStartCommand에서 선택된 언어를 M2M100 언어 코드("__xx__")로 바꿔서 저장해둬요. */
    private var sourceLangCode: String = "__zh__"

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 번역 대기열을 계속 지켜보면서, 문장이 들어오는 대로 순서대로 번역해요.
        // (오디오를 듣는 작업과 완전히 따로 돌아가서, 번역하는 동안에도 다음 소리를 놓치지 않아요)
        //
        // ⚠ translateAndShow 안에서 어떤 이유로든(특히 메모리 부족) 못 잡은 오류가
        // 새어나오면, 이 코루틴이 통째로 죽으면서 같은 부모(serviceScope)를 쓰는
        // 오디오 캡처 코루틴까지 같이 취소되어 "영상 틀면 앱이 꺼지는" 것처럼 보일
        // 수 있어요. translateAndShow 자체가 이미 Throwable을 다 잡아서 내보내지
        // 않지만, 혹시 모를 상황에 대비해 여기서도 한 번 더 감싸서 서비스가 절대
        // 통째로 죽지 않게 해요.
        serviceScope.launch {
            try {
                for (text in translationQueue) {
                    translateAndShow(text)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("TranslateOverlay", "번역 대기열 처리 중 오류", e)
            }
        }
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
        sourceLangCode = m2mLangCode(language)

        showOverlay()
        updateCaptionSync("준비 중…")

        // 번역 모델(M2M100-418M, 온디바이스) 준비 → 음성인식 모델(Vosk) 준비, 순서대로
        // 진행합니다. 번역 모델은 최초 한 번만 인터넷으로 받아두면(약 600MB, 와이파이
        // 권장) 그 다음부터는 완전히 오프라인으로 동작하고 비용도 전혀 없어요.
        serviceScope.launch {
            try {
                val existingPaths = ModelManager.isDownloaded(applicationContext)
                val paths = existingPaths ?: run {
                    updateCaption("번역 모델을 받는 중이에요… (처음 한 번만, 약 600MB, 와이파이 권장)")
                    ModelManager.download(applicationContext) { percent ->
                        updateCaptionSync("번역 모델을 받는 중… ($percent%)")
                    }
                }
                updateCaption("번역 모델을 불러오는 중…")
                translator = withContext(Dispatchers.IO) { NllbTranslator(paths) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val reason = if (e is OutOfMemoryError) {
                    "메모리 부족 (이 기기에서 번역 모델을 불러오기엔 램이 부족해요)"
                } else {
                    e.message ?: e.toString()
                }
                updateCaption("번역 모델 준비 실패 - $reason")
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
        val captionBox = view.findViewById<View>(R.id.captionBox)
        val resizeHandleLeft = view.findViewById<View>(R.id.resizeHandleLeft)
        val resizeHandleRight = view.findViewById<View>(R.id.resizeHandleRight)
        val controlBar = view.findViewById<View>(R.id.controlBar)
        val btnToggleActive = view.findViewById<TextView>(R.id.btnToggleActive)
        val btnClose = view.findViewById<TextView>(R.id.btnClose)
        this.controlBar = controlBar
        this.btnToggleActive = btnToggleActive

        // 버튼 누르면: [활성/비활성] = 일시정지 전환 (누르자마자 글자가 바뀌어서 지금 상태를 보여줘요),
        // [닫기] = 번역 완전히 중지 (창이 사라져요)
        btnToggleActive.setOnClickListener { togglePause() }
        btnClose.setOnClickListener { turnOff() }

        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        // 자막창 너비의 기본값/최소값/최대값 (dp를 화면 px로 변환)
        val density = resources.displayMetrics.density
        val defaultWidthPx = (260 * density).toInt()
        val minWidthPx = (140 * density).toInt()
        val maxWidthPx = resources.displayMetrics.widthPixels - (40 * density).toInt()

        val params = WindowManager.LayoutParams(
            defaultWidthPx,
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

        // 손가락으로 자막창(가운데 박스)을 드래그해서 원하는 위치로 옮길 수 있게
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        // 더블탭 = 위쪽에 [활성/비활성]·[닫기] 버튼 줄을 보였다/숨겼다 해요.
        // (일시정지 자체는 더블탭이 아니라 [활성/비활성] 버튼을 눌러야 바뀌어요)
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleControlBarVisibility()
                return true
            }
        })

        captionBox.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
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

        // 오른쪽 가장자리를 좌우로 끌면 너비가 바뀌어요 (왼쪽 끝은 그대로 고정)
        var resizeStartWidth = 0
        var resizeStartX = 0f

        resizeHandleRight.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    resizeStartWidth = params.width
                    resizeStartX = event.rawX
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newWidth = (resizeStartWidth + (event.rawX - resizeStartX).toInt())
                        .coerceIn(minWidthPx, maxWidthPx)
                    params.width = newWidth
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                else -> false
            }
        }

        // 왼쪽 가장자리를 끌면 오른쪽 끝은 그대로 두고 왼쪽만 늘었다 줄었다 해요
        resizeHandleLeft.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    resizeStartWidth = params.width
                    resizeStartX = event.rawX
                    initialX = params.x
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = (event.rawX - resizeStartX).toInt()
                    val newWidth = (resizeStartWidth - delta).coerceIn(minWidthPx, maxWidthPx)
                    val actualDelta = resizeStartWidth - newWidth
                    params.width = newWidth
                    params.x = initialX + actualDelta
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

    /** 더블탭했을 때: [활성/비활성]·[닫기] 버튼 줄을 보였다/숨겼다 해요. (창 자체는 그대로 떠 있어요) */
    private fun toggleControlBarVisibility() {
        val bar = controlBar ?: return
        val showing = bar.visibility == View.VISIBLE
        if (showing) {
            bar.visibility = View.GONE
        } else {
            updateToggleButtonLabel()
            bar.visibility = View.VISIBLE
        }
    }

    /** [활성/비활성] 버튼을 눌렀을 때: 일시정지 중이면 다시 재생, 재생 중이면 일시정지해요. */
    private fun togglePause() {
        paused = !paused
        updateToggleButtonLabel()
        // 일시정지 중엔 자막창을 살짝 흐리게 해서 지금 멈춰있다는 걸 알 수 있게 해요.
        overlayView?.let { v -> v.post { v.alpha = if (paused) 0.5f else 1f } }
    }

    /** [활성/비활성] 버튼 글자를 지금 상태(재생 중=활성 / 일시정지=비활성)에 맞게 바꿔요. */
    private fun updateToggleButtonLabel() {
        btnToggleActive?.text = if (paused) "비활성" else "활성"
    }

    /** [닫기] 버튼을 눌렀을 때: 서비스를 완전히 끄고 자막창도 화면에서 사라지게 해요. */
    private fun turnOff() {
        stopSelf()
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

        val readBuf = ByteArray(4096)

        serviceScope.launch {
            var lastFinalAt = System.currentTimeMillis()

            while (capturing) {
                val read = try {
                    record.read(readBuf, 0, readBuf.size)
                } catch (e: Exception) {
                    break
                }
                if (read <= 0) continue

                if (paused) {
                    // 일시정지 중이에요 → 오디오는 계속 읽어서 버려요(버퍼가 안 넘치게),
                    // 인식/번역은 하지 않아서 자막창은 그대로 유지돼요.
                    continue
                }

                val client = speechClient ?: continue // 음성인식 모델이 아직 준비 안 됐으면 건너뜀
                val now = System.currentTimeMillis()

                val finalText = client.feed(readBuf, read)
                if (finalText != null) {
                    // 문장 하나가 완성됐어요! 번역 대기열에 넣어두면, 따로 순서대로 번역돼요.
                    lastFinalAt = now
                    translationQueue.trySend(finalText)
                    continue
                }

                if (now - lastFinalAt >= MAX_UTTERANCE_MS) {
                    // 말이 너무 오래 안 끊겨요 → 기다리지 않고 지금까지 들은 걸로 문장을 완성해요.
                    lastFinalAt = now
                    val forced = client.flush()
                    if (forced != null) {
                        translationQueue.trySend(forced)
                    }
                }
            }
        }
    }

    private suspend fun translateAndShow(text: String) {
        val t = translator ?: return
        val translated = try {
            withContext(Dispatchers.Default) {
                t.translate(text, sourceLangCode, TRANSLATE_TARGET_LANG).text
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 원래는 Exception만 잡았는데, 메모리가 부족해서 나는 OutOfMemoryError는
            // Exception이 아니라 Error라서 여기서 못 잡히고 서비스 전체가 조용히
            // 죽어버렸어요(그래서 "듣는 중"이 뜨다가 영상을 틀면 앱이 꺼지는 것처럼
            // 보였던 원인 중 하나예요). Throwable로 바꿔서 이제는 이 문장 하나만
            // 번역 실패로 처리하고(원문 그대로 보여줌), 앱과 자막창은 계속 살아있게 해요.
            Log.e("TranslateOverlay", "번역 실패 (문장 1개, 원문 그대로 표시)", e)
            text
        }
        // "원문" 한 줄, 그 아래에 "번역" 한 줄 - 딱 이 순서로만 보여줘요(요청하신 형태).
        val line = if (SHOW_SOURCE_TEXT_FOR_DEBUG) "원문: $text\n번역: $translated" else translated
        appendCaptionLine(line)
    }

    /** 번역된 문장을 자막창 맨 아래에 추가하고, 화면엔 최근 [MAX_CAPTION_LINES]줄까지만 남겨요. */
    private suspend fun appendCaptionLine(line: String) {
        if (line.isBlank()) return
        captionLines.addLast(line)
        while (captionLines.size > MAX_CAPTION_LINES) {
            captionLines.removeFirst()
        }
        updateCaption(captionLines.joinToString("\n\n"))
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

        translationQueue.close()

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
