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
 */
class VoskSpeechClient private constructor(private val model: Model) {

    private val recognizer = Recognizer(model, 16000.0f)

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

    private fun extractText(json: String): String? =
        try {
            val text = JSONObject(json).optString("text", "").trim()
            if (text.isBlank()) null else text
        } catch (e: Exception) {
            null
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
        private fun modelInfo(language: String): Pair<String, String> = when (language) {
            Prefs.LANG_JAPANESE -> "ja" to "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip"
            Prefs.LANG_ENGLISH -> "en" to "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
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
        fun load(context: Context, language: String, onProgress: (String) -> Unit): VoskSpeechClient? {
            return try {
                val (tag, url) = modelInfo(language)
                val modelDir = File(context.filesDir, "vosk-model-$tag")

                var modelRoot = findModelRoot(modelDir)
                if (modelRoot == null) {
                    onProgress("음성인식 모델을 내려받는 중이에요… (이 언어는 처음이라 한 번만 받으면 돼요, 40~50MB 정도)")
                    downloadAndUnzip(url, modelDir)
                    modelRoot = findModelRoot(modelDir)
                }
                if (modelRoot == null) return null

                VoskSpeechClient(Model(modelRoot.absolutePath))
            } catch (e: Exception) {
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

        private fun downloadAndUnzip(url: String, destDir: File) {
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

                ZipInputStream(body.byteStream()).use { zis ->
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
