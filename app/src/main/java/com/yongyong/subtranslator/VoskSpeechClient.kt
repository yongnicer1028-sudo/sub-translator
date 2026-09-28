package com.yongyong.subtranslator

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * 완전 무료 · 오프라인 음성인식(Vosk) 도우미 클래스.
 * 구글 클라우드 STT와 다르게 API 키가 필요 없고, 분당 과금도 전혀 없어요.
 *
 * - 처음 그 언어를 사용할 때 딱 한 번, 인터넷으로 음성인식 모델(40~50MB 정도)을 받아서
 *   폰 안에 저장해둬요.
 * - 그 다음부터는 인터넷이 없어도 폰 안에서 바로 처리돼요.
 *
 * ⚠ v2 변경점: 예전에는 소리를 3.5초씩 뚝뚝 잘라서, 그때마다 새 인식기를 만들어 따로따로
 * 인식했어요. 그러면 문장이 중간에 잘리고(그래서 번역도 단어 몇 개 수준으로만 나옴),
 * Vosk가 원래 잘하는 "말이 잠깐 멈추면 그게 문장의 끝"이라는 판단도 전혀 못 썼어요.
 * 지금은 인식기 하나를 계속 살려두고 소리를 끊김없이 계속 흘려보내면서, Vosk 스스로
 * "여기서 문장이 끝났다"고 판단하는 순간에만 문장을 완성해서 돌려주는 방식으로 바꿨어요.
 * (이게 Vosk가 원래 쓰이도록 설계된 정석적인 방법이에요)
 *
 * ⚠ v3 변경점: 설정 화면에서 "정확도 우선 모드"를 켤 수 있게 했었어요(작은 모델
 * 대신 용량이 큰(약 1~2GB) 모델을 선택하는 기능). 하지만 그 모델이 태블릿에서
 * 영상 재생과 같이 돌아가면 메모리가 부족해져서 앱이 꺼지는 문제가 있었고,
 * 결국 실제로는 못 쓰는 기능이라 v5에서 완전히 없앴어요 (아래 v5 참고).
 *
 * ⚠ v4 변경점: 모델 크기는 그대로(작은 모델) 두고 번역 품질을 높이는 방법을
 * 대신 넣었어요 — Vosk가 단어 하나하나마다 "이 단어가 맞을 확률(신뢰도)"도 같이
 * 알려주게 설정하고(setWords), 신뢰도가 너무 낮은 단어는 아마 잘못 들었을
 * 가능성이 커서 번역기에 넘기기 전에 걸러내요(extractText 참고). 잘못 들은
 * 단어를 그대로 번역기에 넘기면 번역도 같이 엉뚱해지니, 여기서 먼저 걸러주면
 * 모델은 안 바꿔도 번역 결과가 더 깨끗해져요.
 *
 * ⚠ v5 변경점: v3에서 만든 "정확도 우선 모드"(큰 모델 선택 기능)를 완전히
 * 없앴어요 - 메모리 문제로 어차피 안정적으로 못 쓰는 기능을 옵션으로 남겨두는
 * 것보다, 깔끔하게 지우고 작은 모델 하나만 쓰는 게 더 낫다고 판단했어요.
 * load()가 다시 (context, language, onProgress) 3개만 받고, modelInfo()도
 * 작은 모델 주소만 돌려줘요.
 *
 * ⚠ v6 변경점: 일본어/중국어는 원래 띄어쓰기가 없는 언어인데, Vosk는 인식된
 * 단어를 항상 스페이스로 띄어서 내놔요("正午 が そんな" 처럼). 이 띄어쓰기가 낀
 * 문장을 그대로 번역기(M2M100)에 넘기면, 번역기는 원래 띄어쓰기 없이 붙어있어야
 * 할 글자 사이에 없던 경계가 생긴 것으로 착각해서 단어를 이상하게 잘라 읽을 수
 * 있어요 - 원문(음성인식 결과) 자체는 말이 되는데 번역만 유독 이상하게 나온
 * 사례들이 이래서 생긴 걸로 보여요. 그래서 이 두 언어만 단어를 띄어쓰기 없이
 * 붙이고(중국어는 원래도 띄어쓰기가 없어서 문제가 더 컸어요), 영어처럼 원래
 * 띄어쓰기가 있는 언어는 그대로 스페이스로 이어붙여요 (wordJoinSeparator 참고).
 */

/** 이 값보다 신뢰도(conf)가 낮은 단어는 걸러내요. 너무 높게 잡으면 멀쩡한 단어까지
 *  걸러질 수 있어서, 확실히 잘못 들은 것 같은 단어만 걸러내도록 낮게 잡았어요. */
private const val MIN_WORD_CONFIDENCE = 0.3

class VoskSpeechClient private constructor(private val model: Model, private val language: String) {

    private val recognizer = Recognizer(model, 16000.0f).apply {
        // 위 v4 설명 참고: 단어별 신뢰도를 결과에 포함시켜요.
        setWords(true)
    }

    /**
     * 오디오 조각(pcm16 = 16bit/16000Hz/mono)을 계속 이어서 넣어주는 함수예요.
     * len은 pcm16 배열 중 실제로 유효한 바이트 수예요(배열 전체 크기가 아닐 수 있어요).
     *
     * Vosk가 "여기까지 한 문장이다"라고 스스로 판단하면(예: 사람이 잠깐 말을 멈추면)
     * 그 문장 전체를 돌려주고, 아직 말하는 중이면 null을 돌려줘요.
     */
    fun feed(pcm16: ByteArray, len: Int): String? {
        val isFinal = try {
            recognizer.acceptWaveForm(pcm16, len)
        } catch (e: Exception) {
            return null
        }
        if (!isFinal) return null
        return extractText(recognizer.getResult())
    }

    /** 아직 확정되지 않았지만 지금까지 들리고 있는 말(중간 결과)을 보고 싶을 때 써요. */
    fun partialText(): String {
        return try {
            JSONObject(recognizer.getPartialResult()).optString("partial", "").trim()
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 말이 너무 오래 안 끊기고 계속될 때(예: 8초 넘게), 기다리지 않고 지금까지 들은 걸
     * 강제로 한 문장으로 확정할 때 써요. 그래야 자막이 너무 늦게 뜨는 걸 막을 수 있어요.
     */
    fun flush(): String? = extractText(recognizer.getFinalResult())

    /** 일본어/중국어는 원래 띄어쓰기가 없는 언어라서, 이 두 언어일 땐 단어를 띄어쓰기
     *  없이 붙이고(정리 함수 cleanupSpacing 참고), 그 외 언어는 원래대로 스페이스로
     *  띄어써요. (자세한 이유는 위 v6 설명 참고) */
    private fun wordJoinSeparator(): String =
        if (language == Prefs.LANG_JAPANESE || language == Prefs.LANG_CHINESE) "" else " "

    /** rawText를 그대로 쓸 때(단어별 신뢰도 정보가 없을 때)도, 일본어/중국어라면
     *  Vosk가 넣어둔 단어 사이 스페이스를 없애줘요. */
    private fun cleanupSpacing(text: String): String =
        if (language == Prefs.LANG_JAPANESE || language == Prefs.LANG_CHINESE) text.replace(" ", "") else text

    private fun extractText(json: String): String? {
        return try {
            val root = JSONObject(json)
            val rawText = root.optString("text", "").trim()
            if (rawText.isBlank()) return null

            // setWords(true) 덕분에 "result" 배열로 단어별 신뢰도가 같이 와요. 신뢰도가
            // MIN_WORD_CONFIDENCE보다 낮은 단어(아마 잘못 들었을 가능성이 큰 단어)는
            // 걸러내고 나머지만 이어붙여요. (신뢰도 정보가 없는 경우엔 원문 그대로 써요)
            val wordsArray = root.optJSONArray("result")
            if (wordsArray == null || wordsArray.length() == 0) return cleanupSpacing(rawText)

            val keptWords = mutableListOf<String>()
            for (i in 0 until wordsArray.length()) {
                val w = wordsArray.optJSONObject(i) ?: continue
                val word = w.optString("word", "")
                val conf = w.optDouble("conf", 1.0)
                if (word.isNotBlank() && conf >= MIN_WORD_CONFIDENCE) {
                    keptWords.add(word)
                }
            }

            // 전부 다 걸러졌다면(드문 경우), 자막이 아예 안 뜨는 것보다는 원래 인식된
            // 문장이라도 보여주는 게 나아서 원문 그대로 돌려줘요.
            if (keptWords.isEmpty()) cleanupSpacing(rawText) else keptWords.joinToString(wordJoinSeparator())
        } catch (e: Exception) {
            null
        }
    }

    /** 서비스가 끝날 때 모델이 쓰던 메모리를 정리해줘요. */
    fun close() {
        try {
            recognizer.close()
        } catch (e: Exception) {
        }
        try {
            model.close()
        } catch (e: Exception) {
        }
    }

    companion object {
        // 작은 모델(40~50MB)만 써요. 태그 이름은 예전 그대로 유지해서, 이미 받아둔
        // 사람은 다시 받을 필요가 없어요.
        private fun modelInfo(language: String): Pair<String, String> = when (language) {
            Prefs.LANG_JAPANESE -> "ja" to "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip"
            Prefs.LANG_ENGLISH -> "en" to "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
            Prefs.LANG_RUSSIAN -> "ru" to "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
            Prefs.LANG_GERMAN -> "de" to "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip"
            else -> "cn" to "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
        }

        /**
         * 이 언어의 음성인식 모델이 폰에 이미 있으면 그걸 그대로 불러오고,
         * 없으면 인터넷으로 내려받아서 압축을 풀고 불러와요.
         *
         * ⚠ 시간이 걸릴 수 있는 작업(다운로드/압축해제/모델 불러오기)이라, 반드시
         * 백그라운드 스레드(코루틴 등)에서 호출해야 해요. 실패하면 null을 돌려줘요.
         *
         * onProgress: 지금 뭘 하고 있는지 화면에 보여주고 싶을 때 쓰는 콜백이에요.
         */
        fun load(
            context: Context,
            language: String,
            onProgress: (String) -> Unit
        ): VoskSpeechClient? {
            return try {
                val (tag, url) = modelInfo(language)
                val modelDir = File(context.filesDir, "vosk-model-$tag")

                var modelRoot = findModelRoot(modelDir)
                if (modelRoot == null) {
                    onProgress("음성인식 모델을 내려받는 중이에요… (이 언어는 처음이라 한 번만 받으면 돼요, 40~50MB 정도)")
                    downloadAndUnzip(url, modelDir) { percent ->
                        onProgress("음성인식 모델을 내려받는 중… ($percent%)")
                    }
                    modelRoot = findModelRoot(modelDir)
                }
                if (modelRoot == null) return null

                VoskSpeechClient(Model(modelRoot.absolutePath), language)
            } catch (e: Throwable) {
                // Exception만 잡으면, 메모리가 부족할 때 나는 OutOfMemoryError는
                // Exception이 아니라 Error라서 여기서 못 잡히고 서비스 전체가 조용히
                // 죽어버릴 수 있어요. Throwable로 넓혀서 항상 안전하게 null을 돌려주게 했어요.
                null
            }
        }

        /**
         * 압축을 푼 폴더 안에서 실제 모델 루트(conf 폴더가 있는 곳)를 찾아요.
         * (모델 zip 안에는 보통 "vosk-model-small-cn-0.22" 같은 폴더가 한 겹 더 감싸고 있어요)
         */
        private fun findModelRoot(dir: File): File? {
            if (!dir.exists()) return null
            if (File(dir, "conf").isDirectory) return dir
            return dir.listFiles()?.firstOrNull { it.isDirectory && File(it, "conf").isDirectory }
        }

        private fun downloadAndUnzip(url: String, destDir: File, onPercent: (Int) -> Unit) {
            if (destDir.exists()) destDir.deleteRecursively()
            destDir.mkdirs()

            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .build()
            val request = Request.Builder().url(url).build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("음성인식 모델 다운로드 실패 (${response.code})")
                }
                val body = response.body ?: throw IOException("음성인식 모델 응답이 비어있어요")

                // 파일 크기를 미리 알 수 있으면(대부분의 경우), 내려받은 바이트 수를 세서
                // 진행률(%)을 계산해요. 크기를 못 받아오면(드묾) 그냥 0%로 두고, 실패로
                // 처리하지는 않아요 - 아래에서 1L로 나눗셈만 안전하게 막아둬요.
                val totalBytes = body.contentLength()
                var readSoFar = 0L
                var lastReportedPercent = -1
                val countingStream = object : java.io.FilterInputStream(body.byteStream()) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0 && totalBytes > 0) {
                            readSoFar += n
                            val percent = ((readSoFar.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
                            if (percent != lastReportedPercent) {
                                lastReportedPercent = percent
                                onPercent(percent)
                            }
                        }
                        return n
                    }
                }

                ZipInputStream(countingStream).use { zis ->
                    val buffer = ByteArray(8192)
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(destDir, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                var len: Int
                                while (zis.read(buffer).also { len = it } > 0) {
                                    fos.write(buffer, 0, len)
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        }
    }
}
