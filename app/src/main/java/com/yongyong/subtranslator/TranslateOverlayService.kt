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
import android.graphics.drawable.GradientDrawable
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
import android.widget.ScrollView
import android.widget.SeekBar
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
 *
 * ⚠ v8 변경점: 쉬지 않고 계속 말하는 내레이션 영상에서 8초 분량이 통째로 한
 * 문장이 되어 번역이 느리게 느껴지는 문제가 있어서, 강제로 문장을 끊는 기준을
 * 8초 → 5초로 줄였어요(MAX_UTTERANCE_MS 참고). 자막이 더 짧은 단위로, 더 자주
 * 뜨게 돼요.
 *
 * ⚠ v9 변경점: 진단용으로 원문(음성인식 결과)을 같이 보여준 결과, 번역이 이상했던
 * 진짜 원인이 번역기가 아니라 음성인식(Vosk) 자체가 알아듣는 단계였다는 게
 * 확인됐어요. 그래서 설정 화면에 "정확도 우선 모드"를 추가해서, 원하는 사람은
 * 용량이 큰(약 1~2GB) 더 정확한 모델을 선택할 수 있게 했었어요. 하지만 이 모드는
 * 실제로 영상 재생과 같이 돌리면 메모리 부족으로 꺼지는 경우가 많아 결국 못 쓰는
 * 기능이었고, v10에서 완전히 없앴어요(아래 v10 참고).
 *
 * ⚠ v10 변경점: (1) "정확도 우선 모드"를 없애고, 대신 VoskSpeechClient.kt에서
 * 단어별 신뢰도를 확인해서 신뢰도가 낮은(아마 잘못 들었을) 단어를 걸러내는
 * 방식으로 번역 품질을 개선했어요 - 모델 용량은 그대로 작게 유지돼요. (2) 자막이
 * 계속 쌓이면 자막창이 위로 한없이 늘어나 보이던 문제를 고쳤어요 - 이제 자막이
 * 보이는 부분(captionScroll)은 높이가 정해져 있고, 그 안에서 최근 자막이 자동으로
 * 아래로 스크롤되면서 손가락으로 위/아래를 오가며 지난 자막도 볼 수 있어요. (3)
 * 자막창 아래쪽 가장자리(resizeHandleBottom)를 위아래로 끌면 한 번에 보이는
 * 자막 높이를 조절할 수 있어요(좌우 너비 조절은 기존 그대로예요). 최근 자막을
 * 몇 개까지 기억해둘지(MAX_CAPTION_LINES)도 10 → 30으로 늘려서, 스크롤로 더
 * 많은 과거 자막을 볼 수 있게 했어요.
 *
 * ⚠ v11 변경점: (1) v10에서 captionScroll(스크롤 영역)을 추가한 뒤로 더블탭이
 * 잘 안 먹히는 문제가 있었어요 - 원인은 더블탭/드래그 인식을 captionBox 전체에
 * 걸어뒀는데, 그 안의 captionScroll이 스크롤을 위해 터치를 먼저 가로채면서
 * 더블탭·드래그 제스처가 끝까지 전달이 안 됐던 거예요(자막이 쌓여서 스크롤 가능한
 * 상태가 될수록 더 자주 발생). 그래서 드래그/더블탭 전용 손잡이(dragHandle,
 * 캡션 박스 맨 위 흰 막대)를 스크롤 영역과 분리했어요 - 이제 손잡이를 잡으면
 * 확실하게 이동/더블탭이 되고, captionScroll은 스크롤만 신경 써요. (2) 좌/우/아래
 * 크기 조절 손잡이가 투명해서 어디를 잡아야 할지 안 보이던 문제 - 16dp → 24dp로
 * 넓히고 옅은 흰색 띠로 눈에 보이게 해서 손가락으로 잡기 더 편해졌어요. (3)
 * 일본어/중국어는 띄어쓰기가 없는 언어인데 Vosk가 단어를 스페이스로 띄어서
 * 내놓다 보니, 번역기(M2M100)가 그 스페이스를 실제 띄어쓰기로 착각해서 단어를
 * 이상하게 잘라 읽는 문제가 있었어요(VoskSpeechClient.kt 참고) - 이 두 언어만
 * 띄어쓰기 없이 붙여서 번역기에 넘기도록 고쳤어요.
 *
 * ⚠ v12 변경점: (1) 자막창 배경(검은 박스)의 진하기를 직접 조절할 수 있게 했어요 -
 * dragHandle을 더블탭하면 뜨는 줄에 [투명도] 버튼을 추가했고, 누르면 슬라이더가
 * 나와서 0(완전 투명)~10(지금까지와 같은 진한 검정) 사이로 좌우로 끌어서 조절해요.
 * 글자 자체는 항상 선명하게 보이고, 배경만 옅어져요(captionBox의 배경
 * Drawable에만 알파값을 적용하고, 안의 글자 View는 안 건드리는 방식이에요).
 * 고른 값은 저장해뒀다가 다음에 켤 때도 그대로 적용돼요. (2) 번역 결과가 완전히
 * 빈 문자열로 나오는 경우("요", "응" 같은 아주 짧은 추임새 한두 글자만 인식됐을
 * 때 종종 있었어요)를 처리했어요 - 원문이 아주 짧으면 자막에 아예 안 띄우고
 * 넘어가고, 그보다 길면 "번역: " 뒤가 텅 비어 보이지 않도록 원문이라도 대신
 * 보여줘요.
 *
 * ⚠ v13 변경점: (1) 위/아래 크기 조절(resizeHandleBottom)이 좌/우 조절과 다르게
 * 동작하던 버그를 고쳤어요 - 예전엔 창(window) 자체는 WRAP_CONTENT로 두고
 * captionScroll의 높이만 바꾼 다음 "알아서 맞춰지길" 기다렸는데, 이러면 손잡이가
 * 실제 상자 가장자리랑 따로 노는 것처럼 보였어요("손잡이를 잡고 내리면 위에서
 * 늘어나는 것 같다"는 문제). recomputeWindowHeight()에서 지금 화면에 실제로 보이는
 * 내용을 직접 재서(view.measure) 창 높이(params.height)에 넣는 방식으로 바꿔서,
 * 이제 손잡이가 상자 가장자리에 항상 딱 붙어서 같이 움직여요. 고정된 숫자로만
 * 계산하지 않고 매번 실측하기 때문에, 아래 [크기] 버튼이나 더블탭으로 위쪽 버튼
 * 줄(controlBar/투명도/크기 조절 줄)을 펼쳤을 때도 그만큼 창이 같이 커져서 잘리지
 * 않아요. (2) dragHandle을 더블탭하면 뜨는 줄에 [크기] 버튼을 추가했어요 - 누르면
 * 너비／높이를 각각 －/＋ 버튼으로 한 단계(24dp)씩 정확하게 조절할 수 있어요.
 * 손가락으로 정밀하게 드래그하기 어려울 때 쓰라고 넣었어요.
 *
 * ⚠ v14 변경점: (1) 크기 조절 손잡이(좌/우/아래)가 투명도 설정과 상관없이 항상 똑같은
 * 밝기여서, 캡션 박스를 옅게 만들면 손잡이만 따로 진하게 동동 떠 있는 것처럼 보인다는
 * 말씀을 주셨어요 - 이제 [투명도] 슬라이더를 움직이면 손잡이들도 캡션 박스와 같은
 * 비율로 같이 옅어지고 진해져요(applyCaptionOpacity 참고). (2) [활성/비활성] 버튼을
 * 눌러 비활성으로 바꾸면, 예전엔 자막 상자가 그대로 남은 채 살짝 흐려지기만 했는데,
 * 이제는 자막 상자·크기 조절 손잡이·[투명도]/[크기]/[닫기] 버튼을 전부 숨기고
 * [비활성] 버튼 하나만 화면에 떠 있게 바꿨어요(applyPausedVisualState 참고) - 다시
 * 누르면 원래대로 돌아와요.
 */
class TranslateOverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val NOTIF_CHANNEL_ID = "sub_translator_channel"
        private const val NOTIF_ID = 1001
        private const val SAMPLE_RATE = 16000

        // 원래는 8초였는데, 내레이션처럼 쉬지 않고 계속 말하는 영상에서는 8초 분량이
        // 통째로 한 문장이 되어 버려서(문장이 길수록 번역기가 토큰을 더 많이 만들어야
        // 해서 그만큼 오래 걸려요), 자막이 뜨기까지 체감 지연이 컸어요. 5초로 줄여서
        // 말이 안 끊기는 영상에서도 더 짧은 단위로 자주 끊어 번역하게 했어요 — 자막이
        // 더 빨리, 더 자주 뜨는 대신, 문장이 끊기는 지점이 조금 더 어색할 수 있어요.
        private const val MAX_UTTERANCE_MS = 5000L

        /** 자막창에 최근 번역을 몇 개까지 남겨둘지 (화면엔 스크롤로 지난 자막도 볼 수 있어요) */
        private const val MAX_CAPTION_LINES = 30

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

    /** overlayView를 화면에 띄울 때 쓴 WindowManager 설정값이에요. 크기 조절/버튼 줄
     *  보이기·숨기기 때마다 이 값을 고쳐서 windowManager.updateViewLayout에 다시 넣어줘요. */
    private var overlayParams: WindowManager.LayoutParams? = null
    private var captionText: TextView? = null
    private var captionScroll: ScrollView? = null
    private var captionBox: View? = null

    /** 더블탭하면 나타나는 [활성/비활성]·[투명도]·[닫기] 버튼 줄이에요. 평소엔 숨겨져 있어요. */
    private var controlBar: View? = null
    private var btnToggleActive: TextView? = null

    /** [투명도] 버튼을 누르면 나타나는 슬라이더 줄이에요. 평소엔 숨겨져 있어요. */
    private var opacityRow: View? = null

    /** [크기] 버튼을 누르면 나타나는 －/＋ 버튼 줄이에요. 평소엔 숨겨져 있어요. */
    private var resizeRow: View? = null

    // ⚠ v14: [활성/비활성] 버튼으로 완전히 껐을 때(paused) captionBox와 함께 숨겨야
    // 하는 것들, 그리고 투명도 슬라이더에 맞춰 같이 옅어져야 하는 크기 조절 손잡이들을
    // 여기 인스턴스 필드로 저장해둬요(togglePause/applyCaptionOpacity에서 씀).
    private var resizeHandleLeft: View? = null
    private var resizeHandleRight: View? = null
    private var resizeHandleBottom: View? = null
    private var btnOpacity: TextView? = null
    private var btnResize: TextView? = null
    private var btnClose: TextView? = null

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
        val captionScroll = view.findViewById<ScrollView>(R.id.captionScroll)
        this.captionScroll = captionScroll
        val dragHandle = view.findViewById<View>(R.id.dragHandle)
        val resizeHandleLeft = view.findViewById<View>(R.id.resizeHandleLeft)
        val resizeHandleRight = view.findViewById<View>(R.id.resizeHandleRight)
        val resizeHandleBottom = view.findViewById<View>(R.id.resizeHandleBottom)
        val captionBox = view.findViewById<View>(R.id.captionBox)
        val controlBar = view.findViewById<View>(R.id.controlBar)
        val btnToggleActive = view.findViewById<TextView>(R.id.btnToggleActive)
        val btnOpacity = view.findViewById<TextView>(R.id.btnOpacity)
        val btnResize = view.findViewById<TextView>(R.id.btnResize)
        val btnClose = view.findViewById<TextView>(R.id.btnClose)
        val opacityRow = view.findViewById<View>(R.id.opacityRow)
        val labelOpacity = view.findViewById<TextView>(R.id.labelOpacity)
        val seekOpacity = view.findViewById<SeekBar>(R.id.seekOpacity)
        val resizeRow = view.findViewById<View>(R.id.resizeRow)
        val btnWidthMinus = view.findViewById<TextView>(R.id.btnWidthMinus)
        val btnWidthPlus = view.findViewById<TextView>(R.id.btnWidthPlus)
        val btnHeightMinus = view.findViewById<TextView>(R.id.btnHeightMinus)
        val btnHeightPlus = view.findViewById<TextView>(R.id.btnHeightPlus)
        this.captionBox = captionBox
        this.controlBar = controlBar
        this.btnToggleActive = btnToggleActive
        this.opacityRow = opacityRow
        this.resizeRow = resizeRow
        this.resizeHandleLeft = resizeHandleLeft
        this.resizeHandleRight = resizeHandleRight
        this.resizeHandleBottom = resizeHandleBottom
        this.btnOpacity = btnOpacity
        this.btnResize = btnResize
        this.btnClose = btnClose

        // 버튼 누르면: [활성/비활성] = 일시정지 전환 (누르자마자 글자가 바뀌어서 지금 상태를 보여줘요),
        // [투명도] = 아래 슬라이더 줄 보이기/숨기기, [크기] = 아래 －/＋ 버튼 줄 보이기/숨기기,
        // [닫기] = 번역 완전히 중지 (창이 사라져요)
        btnToggleActive.setOnClickListener { togglePause() }
        btnOpacity.setOnClickListener { toggleOpacityRowVisibility() }
        btnResize.setOnClickListener { toggleResizeRowVisibility() }
        btnClose.setOnClickListener { turnOff() }

        // 저장해둔 투명도 값을 불러와서 슬라이더 초기 위치와 배경에 바로 적용해요.
        val savedOpacity = Prefs.getCaptionOpacity(this)
        seekOpacity.progress = savedOpacity
        applyCaptionOpacity(savedOpacity)
        seekOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                applyCaptionOpacity(progress)
                labelOpacity.text = "투명도 $progress"
                Prefs.setCaptionOpacity(this@TranslateOverlayService, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        labelOpacity.text = "투명도 $savedOpacity"

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

        // 자막이 한 번에 보이는 높이(세로 크기)의 기본값/최소값/최대값. 이 높이보다
        // 자막 내용이 길어지면 화면을 계속 키우는 대신 captionScroll 안에서 스크롤돼요.
        val defaultScrollHeightPx = (220 * density).toInt()
        val minScrollHeightPx = (80 * density).toInt()
        val maxScrollHeightPx = resources.displayMetrics.heightPixels - (200 * density).toInt()

        // ⚠ v13: 예전엔 창(window) 자체 높이를 WRAP_CONTENT로 두고 captionScroll
        // 높이만 바꾼 다음 "알아서 맞춰지길" 기다렸는데, 그러면 손잡이(resizeHandleBottom)가
        // 실제 상자 가장자리랑 따로 노는 것처럼 보이는 문제가 있었어요("손잡이를 잡고
        // 내리면 위에서 늘어나는 것 같다"고 하셨던 게 이거예요). 그래서 지금 화면에
        // 실제로 보이는 내용(캡션 상자 + 혹시 열려 있는 버튼 줄들)에 맞춰서 창 높이를
        // 직접 재보고(recomputeWindowHeight) 넣어주는 방식으로 바꿨어요 - 이러면
        // 손잡이가 상자 가장자리에 항상 정확히 붙어있고, 버튼 줄(controlBar/투명도/크기)을
        // 열었을 때도 창이 같이 커져서 잘리지 않아요. (46dp 같은 값을 고정해서 계산하면
        // 캡션 상자만 있을 땐 맞지만, 버튼 줄이 펼쳐졌을 때는 그만큼 공간이 모자라서
        // 잘리는 문제가 생겨요 - 그래서 고정값 대신 실측 방식을 썼어요)
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
        captionScroll.layoutParams = captionScroll.layoutParams.apply { height = defaultScrollHeightPx }

        // addView 하기 전에 실제 필요한 높이를 미리 한 번 재서, 처음부터 정확한 숫자
        // 높이로 시작해요(그래야 나중에 손잡이/버튼으로 조절할 때도 일관돼요).
        view.measure(
            View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.heightPixels, View.MeasureSpec.AT_MOST)
        )
        params.height = view.measuredHeight

        // 좌/우 너비, 위/아래 높이를 실제로 적용하는 부분을 한 곳에 모아뒀어요. 드래그로
        // 조절할 때도, 아래 [크기] 버튼(－/＋)으로 조절할 때도 이 두 함수를 같이 써서
        // 항상 똑같은 방식으로 동작해요.
        fun applyWidth(newWidthPx: Int) {
            params.width = newWidthPx.coerceIn(minWidthPx, maxWidthPx)
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {
            }
        }

        fun applyScrollHeight(newScrollHeightPx: Int) {
            val clamped = newScrollHeightPx.coerceIn(minScrollHeightPx, maxScrollHeightPx)
            captionScroll.layoutParams = captionScroll.layoutParams.apply { height = clamped }
            recomputeWindowHeight()
        }

        // 손가락으로 자막창(가운데 박스)을 드래그해서 원하는 위치로 옮길 수 있게
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        // 더블탭 = 위쪽에 [활성/비활성]·[투명도]·[크기]·[닫기] 버튼 줄을 보였다/숨겼다 해요.
        // (일시정지 자체는 더블탭이 아니라 [활성/비활성] 버튼을 눌러야 바뀌어요)
        //
        // ⚠ v11: 이 리스너는 원래 captionBox(글자 영역 포함) 전체에 걸려있었는데,
        // 그 안의 captionScroll이 스크롤을 위해 터치를 먼저 가져가버려서 더블탭이나
        // 드래그가 끝까지 전달이 안 되는 경우가 있었어요. 그래서 스크롤 영역과
        // 겹치지 않는 전용 손잡이(dragHandle)에만 걸어서, 항상 확실하게 동작하게
        // 했어요.
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleControlBarVisibility()
                return true
            }
        })

        dragHandle.setOnTouchListener { _, event ->
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
                    applyWidth(resizeStartWidth + (event.rawX - resizeStartX).toInt())
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
                    params.x = initialX + actualDelta
                    applyWidth(newWidth)
                    true
                }
                else -> false
            }
        }

        // 아래쪽 가장자리를 위아래로 끌면 captionScroll의 높이(한 번에 보이는 자막 양)가
        // 바뀌어요. applyScrollHeight()가 창(window) 높이까지 같이 계산해서 넣어주기
        // 때문에, 손잡이가 상자 가장자리에서 벗어나지 않고 항상 붙어서 움직여요.
        var resizeStartHeight = 0
        var resizeStartY = 0f

        resizeHandleBottom.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    resizeStartHeight = captionScroll.layoutParams.height
                    resizeStartY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    applyScrollHeight(resizeStartHeight + (event.rawY - resizeStartY).toInt())
                    true
                }
                else -> false
            }
        }

        // [크기] 버튼을 누르면 나오는 －/＋ 버튼들. 드래그가 정밀하게 잘 안 될 때를 위한
        // 대안이에요 - 누를 때마다 한 단계(24dp)씩 정확하게 커지거나 작아져요.
        val resizeStepPx = (24 * density).toInt()
        btnWidthMinus.setOnClickListener { applyWidth(params.width - resizeStepPx) }
        btnWidthPlus.setOnClickListener { applyWidth(params.width + resizeStepPx) }
        btnHeightMinus.setOnClickListener { applyScrollHeight(captionScroll.layoutParams.height - resizeStepPx) }
        btnHeightPlus.setOnClickListener { applyScrollHeight(captionScroll.layoutParams.height + resizeStepPx) }

        try {
            windowManager.addView(view, params)
            overlayView = view
            overlayParams = params
        } catch (e: Exception) {
            // 오버레이 권한이 없는 등 예외 상황 - 알림으로만 상태를 알림
        }
    }

    /** 창(window) 높이를 지금 화면에 실제로 보이는 내용(캡션 상자 + 혹시 열려 있는
     *  controlBar/opacityRow/resizeRow)에 딱 맞게 다시 재서 넣어줘요. 자막 높이를
     *  손잡이/버튼으로 조절할 때, 그리고 더블탭이나 [투명도]/[크기] 버튼으로 위쪽
     *  버튼 줄들을 보였다 숨겼다 할 때마다 이 함수를 불러요 - 그래야 손잡이는 항상
     *  상자 가장자리에 붙어있고, 버튼 줄이 펼쳐졌을 때도 잘리지 않고 창이 같이 커져요. */
    private fun recomputeWindowHeight() {
        val view = overlayView ?: return
        val params = overlayParams ?: return
        view.measure(
            View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.heightPixels, View.MeasureSpec.AT_MOST)
        )
        params.height = view.measuredHeight
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    /** 더블탭했을 때: [활성/비활성]·[투명도]·[크기]·[닫기] 버튼 줄을 보였다/숨겼다 해요.
     *  (창 자체는 그대로 떠 있어요) 버튼 줄을 숨길 땐 투명도 슬라이더/크기 조절 줄도
     *  같이 접어요 - 버튼이 안 보이는 상태에서 그 아래 것들만 계속 떠 있으면
     *  헷갈리니까요. */
    private fun toggleControlBarVisibility() {
        val bar = controlBar ?: return
        val showing = bar.visibility == View.VISIBLE
        if (showing) {
            bar.visibility = View.GONE
            opacityRow?.visibility = View.GONE
            resizeRow?.visibility = View.GONE
        } else {
            updateToggleButtonLabel()
            bar.visibility = View.VISIBLE
        }
        recomputeWindowHeight()
    }

    /** [투명도] 버튼을 눌렀을 때: 슬라이더 줄을 보였다/숨겼다 해요. */
    private fun toggleOpacityRowVisibility() {
        val row = opacityRow ?: return
        row.visibility = if (row.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        recomputeWindowHeight()
    }

    /** [크기] 버튼을 눌렀을 때: －/＋ 버튼 줄을 보였다/숨겼다 해요. */
    private fun toggleResizeRowVisibility() {
        val row = resizeRow ?: return
        row.visibility = if (row.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        recomputeWindowHeight()
    }

    /** captionBox의 배경(검은 둥근 박스)과 좌/우/아래 크기 조절 손잡이의 진하기를 같이
     *  바꿔요. 0(완전 투명)~10(원래처럼 진한 검정) 사이 값을 받아서 실제 알파값으로
     *  바꿔 적용해요.
     *
     *  ⚠ btnToggleActive/btnOpacity/btnClose도 같은 @drawable/overlay_bubble_bg를 배경으로
     *  써요. Drawable 리소스는 여러 View가 기본적으로 같은 인스턴스를 공유할 수 있어서,
     *  mutate() 없이 바로 alpha를 바꾸면 그 버튼들 배경까지 같이 옅어질 수 있어요.
     *  mutate()로 captionBox만의 독립적인 Drawable 사본을 만든 다음 바꿔서, 다른 곳엔
     *  영향이 안 가게 했어요.
     *
     *  ⚠ v14: 크기 조절 손잡이(resizeHandleLeft/Right/Bottom)는 지금까지 투명도 설정과
     *  상관없이 항상 똑같은 밝기(#22FFFFFF)였어요 - 그래서 캡션 박스는 옅어지는데 손잡이만
     *  그대로 진하게 남아있으면, 손잡이가 상자에서 따로 동동 떠 있는 것처럼 보인다는
     *  말씀을 주셨어요. 이제 손잡이들도 View 자체의 alpha(0~1)를 캡션 박스와 같은
     *  비율로 같이 바꿔서, 옅어질 땐 손잡이도 같이 옅어지고 진해질 땐 같이 진해져요. */
    private fun applyCaptionOpacity(level: Int) {
        val clamped = level.coerceIn(0, 10)
        val alpha = (clamped * 255 / 10).coerceIn(0, 255)
        (captionBox?.background?.mutate() as? GradientDrawable)?.alpha = alpha

        val handleAlphaFraction = clamped / 10f
        resizeHandleLeft?.alpha = handleAlphaFraction
        resizeHandleRight?.alpha = handleAlphaFraction
        resizeHandleBottom?.alpha = handleAlphaFraction
    }

    /** [활성/비활성] 버튼을 눌렀을 때: 일시정지 중이면 다시 재생, 재생 중이면 일시정지해요.
     *
     *  ⚠ v14: 예전엔 비활성으로 바꿔도 자막 상자가 그대로 화면에 남아있고 살짝 흐려지기만
     *  했는데, "비활성 버튼은 아예 창을 닫아주고 비활성 버튼만 떠다니게 해달라"는 말씀을
     *  주셔서, 이제 비활성 상태에선 자막 상자·크기 조절 손잡이·[투명도]/[크기]/[닫기]
     *  버튼을 전부 숨기고 [비활성] 버튼 하나만 화면에 떠 있게 바꿨어요(applyPausedVisualState
     *  참고). 다시 누르면 원래대로 자막 상자가 돌아와요. */
    private fun togglePause() {
        paused = !paused
        updateToggleButtonLabel()
        applyPausedVisualState()
    }

    /** [활성/비활성] 버튼 글자를 지금 상태(재생 중=활성 / 일시정지=비활성)에 맞게 바꿔요. */
    private fun updateToggleButtonLabel() {
        btnToggleActive?.text = if (paused) "비활성" else "활성"
    }

    /** 비활성 상태로 바뀌면: 자막 상자, 크기 조절 손잡이, [투명도]/[크기]/[닫기] 버튼을
     *  전부 숨기고 [비활성] 버튼 하나만 화면에 남겨요(창 크기도 그만큼 줄어들어요).
     *  다시 활성으로 바뀌면 전부 원래대로 돌아오고, 버튼 줄(controlBar)은 원래
     *  기본값대로 다시 숨겨져요(더블탭하면 다시 열려요). */
    private fun applyPausedVisualState() {
        val nowPaused = paused
        captionBox?.visibility = if (nowPaused) View.GONE else View.VISIBLE
        resizeHandleLeft?.visibility = if (nowPaused) View.GONE else View.VISIBLE
        resizeHandleRight?.visibility = if (nowPaused) View.GONE else View.VISIBLE
        resizeHandleBottom?.visibility = if (nowPaused) View.GONE else View.VISIBLE
        btnOpacity?.visibility = if (nowPaused) View.GONE else View.VISIBLE
        btnResize?.visibility = if (nowPaused) View.GONE else View.VISIBLE
        btnClose?.visibility = if (nowPaused) View.GONE else View.VISIBLE
        opacityRow?.visibility = View.GONE
        resizeRow?.visibility = View.GONE
        controlBar?.visibility = if (nowPaused) View.VISIBLE else View.GONE
        recomputeWindowHeight()
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

        // ⚠ v12: "요", "응" 처럼 아주 짧은 추임새 한두 글자만 인식됐을 때, 번역 결과가
        // 통째로 빈 문자열로 나오는 경우가 있었어요. 원문마저 짧으면(별 내용이 없다는
        // 뜻이라) 자막에 아예 안 띄우고 넘어가고, 그보다 긴 문장인데 번역만 어쩌다
        // 비었으면 "번역: " 뒤가 텅 빈 채로 보이지 않게 원문이라도 대신 보여줘요.
        if (translated.isBlank()) {
            if (text.length <= 2) return
        }
        val shownTranslation = translated.ifBlank { text }

        // "원문" 한 줄, 그 아래에 "번역" 한 줄 - 딱 이 순서로만 보여줘요(요청하신 형태).
        val line = if (SHOW_SOURCE_TEXT_FOR_DEBUG) "원문: $text\n번역: $shownTranslation" else shownTranslation
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
            scrollCaptionToBottom()
        }
    }

    /** onStartCommand처럼 suspend가 아닌 곳에서 자막을 바로 바꾸고 싶을 때 */
    private fun updateCaptionSync(text: String) {
        captionText?.post {
            captionText?.text = text
            scrollCaptionToBottom()
        }
    }

    /** 자막을 바꾼 직후, 스크롤 영역을 맨 아래(가장 최근 자막)로 옮겨줘요. 사람이 손가락으로
     *  위로 올려서 지난 자막을 보고 있다가도, 새 자막이 뜨면 다시 최신 내용으로 따라가요. */
    private fun scrollCaptionToBottom() {
        captionScroll?.post { captionScroll?.fullScroll(View.FOCUS_DOWN) }
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
        overlayParams = null

        serviceJob.cancel()
        super.onDestroy()
    }
}
