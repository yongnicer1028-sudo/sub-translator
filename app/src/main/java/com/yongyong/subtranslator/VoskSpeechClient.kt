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
 */
class VoskSpeechClient private constructor(private val model: Model) {

    /**
     * pcm16 = 16bit / 16000Hz / mono 원시 오디오 바이트.
     * 반환값 = 인식된 문장. 실패하거나 무음이면 빈 문자열("")을 돌려줘요.
     */
    fun recognize(pcm16: ByteArray): String {
        return try {
            val recognizer = Recognizer(model, 16000.0f)
            recognizer.acceptWaveForm(pcm16, pcm16.size)
            // getFinalResult(): 무음을 기다리지 않고, 지금까지 들어온 소리를 바로 문장으로
            // 만들어줘요. (이 앱은 3.5초 단위로 소리를 잘라서 넘기기 때문에 이 방식이 맞아요)
            val json = recognizer.getFinalResult()
            recognizer.close()
            JSONObject(json).optString("text", "").trim()
        } catch (e: Exception) {
            ""
        }
    }

    /** 서비스가 끝날 때 모델이 쓰던 메모리를 정리해줘요. */
    fun close() {
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
