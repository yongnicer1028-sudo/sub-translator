package com.yongyong.subtranslator

// ─────────────────────────────────────────────────────────────────────────
// 온디바이스(오프라인) 번역 엔진 — M2M100-418M
//
// 예전엔 ML Kit(구글의 무료 온디바이스 번역)을 썼는데, 번역이 직역투로 어색하다는
// 문제가 있었어요. 그래서 별도 테스트 앱에서 검증한 NLLB-200-distilled-600M으로
// 한번 바꿨는데, 이 모델(약 900MB)이 음성인식(Vosk) 모델과 같이 메모리에 떠
// 있으니 실제 태블릿에서 영상을 재생하면 메모리가 부족해져서 앱이 조용히 꺼지는
// 문제가 있었어요. 그래서 같은 테스트 앱에서 이미 따로 검증까지 끝낸, 훨씬 가벼운
// M2M100-418M(양자화 기준 약 600MB)으로 다시 바꿔서 우선 확실히 동작부터 하게
// 만들었어요. (번역 품질은 NLLB-200이 조금 더 자연스러웠지만, 지금은 "꺼지지
// 않고 계속 동작하는 것"이 더 중요해서 이렇게 바꿨어요. 나중에 여유가 되면
// 다시 NLLB-200으로 바꿔볼 수 있어요)
//
// M2M100은 언어 코드를 "__ja__", "__ko__" 처럼 두 글자 코드 + 밑줄 두 개로
// 표기해요. 이 앱이 지원하는 5개 언어(중국어/일본어/영어/러시아어/독일어)와
// 한국어를 전부 모델 하나로 커버할 수 있어요 (언어마다 따로 모델을 받을 필요가
// 없어요).
//
// ⚠️ 그리디(매번 제일 확률 높은 토큰만 고르는) 방식은 가끔 같은 표현을 끝없이
// 반복하는 유명한 버그가 있어서, "최근 나온 표현이 이미 나왔으면 그 다음 토큰은
// 후보에서 제외"하는 안전장치(no-repeat-ngram)를 넣어뒀어요.
// ─────────────────────────────────────────────────────────────────────────

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

private const val NLLB_TAG = "NllbTranslator"

/** 번역 목표 언어는 항상 한국어예요. (M2M100 언어 코드 표기법: "__xx__") */
const val TRANSLATE_TARGET_LANG = "__ko__"

/** Prefs에 저장된 소스 언어 코드(zh/ja/en/ru/de)를 M2M100이 쓰는 "__xx__" 코드로 바꿔줘요. */
fun m2mLangCode(prefsLanguage: String): String = when (prefsLanguage) {
    Prefs.LANG_JAPANESE -> "__ja__"
    Prefs.LANG_ENGLISH -> "__en__"
    Prefs.LANG_RUSSIAN -> "__ru__"
    Prefs.LANG_GERMAN -> "__de__"
    else -> "__zh__" // Prefs.LANG_CHINESE
}

data class ModelPaths(
    val encoderPath: String,
    val decoderPath: String,
    val tokenizerPath: String
)

object ModelManager {

    // 태블릿에서 NLLB-200(약 900MB)이 음성인식 모델과 같이 메모리에 떠 있다가
    // 꺼지는 문제가 있어서, 별도 테스트 앱에서 이미 검증된 더 가벼운
    // M2M100-418M(양자화 기준 약 600MB)으로 되돌렸어요.
    private const val REPO = "Xenova/m2m100_418M"
    private const val API_URL = "https://huggingface.co/api/models/$REPO"
    private const val FILE_BASE_URL = "https://huggingface.co/$REPO/resolve/main/"

    // M2M100 저장소 원본 tokenizer.json에는 merge 규칙 1,053개가 vocab에 없는
    // 결과를 만드는 버그가 있어서, 별도 테스트 앱에서 미리 고쳐서 올려둔 버전을
    // 그대로 가져와요.
    private const val TOKENIZER_URL =
        "https://github.com/yongnicer1028-sudo/nllb-translate-test/releases/download/tokenizer-fixed-m2m100/tokenizer.json"

    // 파일 선택 로직(resolveFileNames)이나 모델 자체를 나중에 바꾸면 이 숫자를 올려주세요.
    // 그래야 기기에 이미 받아둔 (NLLB-200 같은) 옛날 파일을 무시하고 새로 받아요.
    // NLLB-200 → M2M100으로 모델을 바꿨으니 이번엔 값을 올려요.
    private const val MODEL_SCHEMA_VERSION = 2
    private const val TOKENIZER_VERSION = 2

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun modelsDir(context: android.content.Context): File {
        val dir = File(context.filesDir, "nllb_model")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 이미 파일 3개가 전부 정상적으로 다운로드되어 있는지 확인. */
    fun isDownloaded(context: android.content.Context): ModelPaths? {
        val dir = modelsDir(context)
        val versionFile = File(dir, "schema_version.txt")
        val savedVersion = if (versionFile.exists()) {
            versionFile.readText().trim().toIntOrNull()
        } else null
        if (savedVersion != MODEL_SCHEMA_VERSION) return null

        val enc = File(dir, "encoder.onnx")
        val dec = File(dir, "decoder.onnx")
        val tok = File(dir, "tokenizer.json")
        if (enc.length() <= 0 || dec.length() <= 0 || tok.length() <= 0) return null

        val tokVersionFile = File(dir, "tokenizer_fix_version.txt")
        val savedTokVersion = if (tokVersionFile.exists()) {
            tokVersionFile.readText().trim().toIntOrNull()
        } else null
        if (savedTokVersion != TOKENIZER_VERSION) return null

        return ModelPaths(enc.absolutePath, dec.absolutePath, tok.absolutePath)
    }

    /**
     * huggingface 저장소의 실제 파일 목록을 조회해서, 인코더/디코더 onnx 파일
     * 이름이 정확히 뭔지 찾아내요.
     */
    private fun resolveFileNames(): Pair<String, String> {
        val request = Request.Builder().url(API_URL).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw IllegalStateException("모델 파일 목록을 못 가져왔어요.")
            val root = org.json.JSONObject(body)
            val siblings: JSONArray = root.getJSONArray("siblings")
            val names = mutableListOf<String>()
            for (i in 0 until siblings.length()) {
                names.add(siblings.getJSONObject(i).getString("rfilename"))
            }

            // 파일 이름 우선순위: "_quantized.onnx" 로 끝나는 게 제일 작고 가벼운 표준
            // 8비트 양자화 버전이에요. "int8"/"uint8" 이라는 이름만 보면 더 작을 것 같지만,
            // 실제로는(NLLB-200 기준) quantized 파일보다 3배 넘게 큰 경우가 있어서 이름만
            // 보고 고르면 안 돼요 — 정확한 접미사로 구분해요.
            fun priority(name: String): Int = when {
                name.endsWith("_quantized.onnx") -> 0
                name.contains("int8") || name.contains("uint8") -> 1
                else -> 2
            }

            val encoderName = names
                .filter { it.startsWith("onnx/") && it.contains("encoder_model") && it.endsWith(".onnx") }
                .sortedBy { priority(it) }
                .firstOrNull()
                ?: throw IllegalStateException("인코더(.onnx) 파일을 저장소에서 못 찾았어요. 전체 목록: $names")

            val plainDecoder = names
                .filter {
                    it.startsWith("onnx/") && it.contains("decoder_model") &&
                        !it.contains("merged") && !it.contains("with_past") && it.endsWith(".onnx")
                }
                .sortedBy { priority(it) }
                .firstOrNull()
            val mergedDecoder = names
                .filter { it.startsWith("onnx/") && it.contains("decoder_model_merged") && it.endsWith(".onnx") }
                .sortedBy { priority(it) }
                .firstOrNull()
            val decoderName = plainDecoder ?: mergedDecoder
                ?: throw IllegalStateException("디코더(.onnx) 파일을 저장소에서 못 찾았어요. 전체 목록: $names")

            Log.i(NLLB_TAG, "선택된 파일 -> encoder: $encoderName, decoder: $decoderName")
            return Pair(encoderName, decoderName)
        }
    }

    /**
     * 필요한 파일만 골라서 다운로드해요 — 이미 정상적으로 받아둔 파일은 다시 안 받아요.
     * [onProgress] 는 0~100 사이 값으로, 이번에 실제로 받는 파일들 기준 진행률을 알려줘요.
     */
    suspend fun download(context: android.content.Context, onProgress: (Int) -> Unit): ModelPaths {
        val dir = modelsDir(context)
        val encFile = File(dir, "encoder.onnx")
        val decFile = File(dir, "decoder.onnx")
        val tokFile = File(dir, "tokenizer.json")

        val schemaOk = File(dir, "schema_version.txt").let {
            it.exists() && it.readText().trim().toIntOrNull() == MODEL_SCHEMA_VERSION
        }
        val encDecReady = schemaOk && encFile.length() > 0 && decFile.length() > 0

        val targets = mutableListOf<Pair<String, File>>()
        if (!encDecReady) {
            val (encoderName, decoderName) = resolveFileNames()
            targets.add((FILE_BASE_URL + encoderName) to encFile)
            targets.add((FILE_BASE_URL + decoderName) to decFile)
        }

        val tokVersionFile = File(dir, "tokenizer_fix_version.txt")
        val tokReady = tokFile.length() > 0 &&
            tokVersionFile.exists() &&
            tokVersionFile.readText().trim().toIntOrNull() == TOKENIZER_VERSION
        if (!tokReady) {
            targets.add(TOKENIZER_URL to tokFile)
        }

        if (targets.isNotEmpty()) {
            val sizes = LongArray(targets.size)
            for (i in targets.indices) {
                sizes[i] = headContentLength(targets[i].first)
            }
            val totalBytes = sizes.sum().coerceAtLeast(1L)
            var doneBytesBase = 0L
            var lastReportedPercent = -1

            for (i in targets.indices) {
                val (url, localFile) = targets[i]
                downloadOne(url, localFile) { bytesSoFarThisFile ->
                    val overall = ((doneBytesBase + bytesSoFarThisFile).toDouble() / totalBytes * 100).toInt()
                        .coerceIn(0, 100)
                    if (overall != lastReportedPercent) {
                        lastReportedPercent = overall
                        onProgress(overall)
                    }
                }
                doneBytesBase += sizes[i]
            }
        }

        onProgress(100)
        File(dir, "schema_version.txt").writeText(MODEL_SCHEMA_VERSION.toString())
        tokVersionFile.writeText(TOKENIZER_VERSION.toString())
        return ModelPaths(
            encFile.absolutePath,
            decFile.absolutePath,
            tokFile.absolutePath
        )
    }

    private fun headContentLength(url: String): Long {
        return try {
            val request = Request.Builder().url(url).head().build()
            client.newCall(request).execute().use { it.header("Content-Length")?.toLongOrNull() ?: 0L }
        } catch (e: Exception) {
            0L
        }
    }

    private fun downloadOne(url: String, dest: File, onBytes: (Long) -> Unit) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("다운로드 실패 ($url): HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("다운로드 실패 ($url): 내용 없음")
            body.byteStream().use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    var total = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        total += bytesRead
                        onBytes(total)
                    }
                }
            }
        }
    }
}

data class TranslateResult(val text: String, val elapsedMs: Long)

class NllbTranslator(paths: ModelPaths) : AutoCloseable {

    companion object {
        private const val EOS_ID = 2L
        private const val DECODER_START_TOKEN_ID = 2L

        // 한 토큰을 만들 때마다 디코더를 한 번씩 통째로 다시 돌려야 해서(캐시 없는 구조),
        // 숫자가 클수록 한 문장 번역이 느려져요. 실시간 자막은 8초 안에 말한 내용
        // (MAX_UTTERANCE_MS)만 번역하면 되니 그렇게 긴 문장이 나올 일이 없어서, 80 →
        // 48로 줄였어요. 번역이 눈에 띄게 느려졌다는 문제와, 모델이 끝맺음 토큰을
        // 놓치고 엉뚱한 말을 계속 이어붙이는(할루시네이션) 문제를 동시에 줄여줘요.
        private const val MAX_NEW_TOKENS = 48

        // 그리디 방식은 가끔 같은 표현을 끝없이 반복하는 버그가 있어서(예:
        // "아니, 아니, 아니, ..."), 최근에 나온 표현이 이미 나온 적 있으면
        // 그 다음 토큰을 후보에서 제외하는 안전장치예요.
        private const val NO_REPEAT_NGRAM_SIZE = 3
    }

    private val env = OrtEnvironment.getEnvironment()

    /**
     * 모델을 불러올 때 쓰는 옵션이에요.
     *
     * ⚠ 스레드를 1개로 두면(옛날 설정) 태블릿에서 영상 재생 + 음성인식 + 번역이 동시에
     * 돌아갈 때 번역 하나에 너무 오래 걸리고(체감상 매우 느림), 그동안 기기 전체가
     * 버벅여서 다른 화면 터치도 잘 안 먹는 것처럼 느껴질 수 있어요. M2M100-418M은
     * NLLB-200보다 가벼워서 메모리 여유가 좀 더 있으니, 스레드를 2개로 늘려서 번역
     * 속도를 좀 더 확보했어요 (그 대신 메모리를 아주 조금 더 써요).
     *
     * ⚠ 최적화 단계(OptLevel)를 BASIC → ALL로 올렸어요. 이건 모델의 입력/출력이나
     * 번역 결과는 전혀 안 바꾸고, ONNX Runtime이 내부적으로 계산 그래프를 더
     * 효율적인 형태로 미리 정리해두는 것뿐이라(예: 여러 연산을 하나로 합치기)
     * 안전하면서도 실제로 속도에 도움이 돼요. 다만 모델을 불러오는 바로 그 순간에
     * 이 정리 작업을 하느라 메모리를 잠깐 더 쓰는데, M2M100은 가벼워서 여유가 있어요.
     */
    private fun lightweightSessionOptions(): OrtSession.SessionOptions =
        OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(2)
        }

    private val encoderSession: OrtSession = env.createSession(paths.encoderPath, lightweightSessionOptions())
    private val decoderSession: OrtSession = env.createSession(paths.decoderPath, lightweightSessionOptions())
    private val tokenizer: HuggingFaceTokenizer = HuggingFaceTokenizer.newInstance(File(paths.tokenizerPath).toPath())

    init {
        val decoderInputs = decoderSession.inputNames
        if (decoderInputs.any { it.contains("past_key_values") || it.contains("use_cache_branch") }) {
            throw UnsupportedOperationException(
                "다운로드된 번역모델의 디코더가 '캐시 지원(merged)' 형식이라 지금 코드가 아직 처리 못 해요.\n" +
                    "디코더 입력 목록: $decoderInputs"
            )
        }
    }

    // 언어 코드("__ja__" 등)의 숫자 id는 한 번 구하면 절대 안 바뀌는 값이라(같은 모델을
    // 계속 쓰는 동안엔 고정), 문장 하나 번역할 때마다 매번 다시 찾지 않고 한 번만
    // 계산해서 캐시해둬요. (아주 작은 절약이지만, 그만큼 매 문장마다 불필요한 작업이
    // 줄어서 속도에 조금이라도 도움이 돼요)
    private val langTokenCache = mutableMapOf<String, Long>()

    /** 문자열로 된 언어 코드(예: "__ja__")의 실제 내부 숫자 id를 tokenizer.json에서 읽어와요. */
    private fun langTokenId(langCode: String): Long = langTokenCache.getOrPut(langCode) {
        val encoding = tokenizer.encode(langCode, false, false)
        val ids = encoding.ids
        if (ids.size != 1) {
            throw IllegalStateException(
                "언어 코드 '$langCode' 를 토큰 1개로 인식하지 못했어요 (결과: ${ids.toList()})."
            )
        }
        ids[0]
    }

    /** 최근에 나온 (NO_REPEAT_NGRAM_SIZE - 1)개 토큰 패턴이 예전에도 나온 적 있다면,
     *  그 뒤에 이어졌던 토큰을 이번에는 후보에서 빼서 같은 구절이 무한 반복되는 걸 막아요. */
    private fun bannedNextTokens(generated: List<Long>): Set<Int> {
        val prefixLen = NO_REPEAT_NGRAM_SIZE - 1
        if (generated.size < prefixLen) return emptySet()
        val prefix = generated.takeLast(prefixLen)
        val banned = mutableSetOf<Int>()
        for (i in 0..generated.size - prefixLen - 1) {
            if (generated.subList(i, i + prefixLen) == prefix) {
                banned.add(generated[i + prefixLen].toInt())
            }
        }
        return banned
    }

    fun translate(text: String, srcLang: String, tgtLang: String): TranslateResult {
        val startTime = System.currentTimeMillis()

        val srcId = langTokenId(srcLang)
        val tgtId = langTokenId(tgtLang)
        val bodyIds = tokenizer.encode(text, false, false).ids

        val inputIds = LongArray(bodyIds.size + 2)
        inputIds[0] = srcId
        for (i in bodyIds.indices) inputIds[i + 1] = bodyIds[i]
        inputIds[inputIds.size - 1] = EOS_ID
        val attnMask = LongArray(inputIds.size) { 1L }

        OnnxTensor.createTensor(env, arrayOf(inputIds)).use { inputTensor ->
            OnnxTensor.createTensor(env, arrayOf(attnMask)).use { maskTensor ->
                encoderSession.run(mapOf("input_ids" to inputTensor, "attention_mask" to maskTensor)).use { encoderResult ->
                    val encoderHidden = encoderResult.get("last_hidden_state")
                        .orElseThrow {
                            IllegalStateException(
                                "인코더 출력에서 last_hidden_state를 못 찾았어요. " +
                                    "실제 출력 이름: ${encoderSession.outputNames}"
                            )
                        } as OnnxTensor

                    // 디코더가 이 입력을 받는지는 모델 파일 하나에 대해 항상 똑같아서(문장마다,
                    // 토큰마다 안 바뀜), 토큰을 만들 때마다(최대 48번) 매번 다시 확인하지 않고
                    // 반복문 밖에서 딱 한 번만 확인해요.
                    val decoderWantsEncoderMask = decoderSession.inputNames.contains("encoder_attention_mask")

                    val generated = mutableListOf(DECODER_START_TOKEN_ID, tgtId)
                    var steps = 0
                    while (steps < MAX_NEW_TOKENS) {
                        val decInputIds = generated.toLongArray()
                        val banned = bannedNextTokens(generated)
                        val nextId = OnnxTensor.createTensor(env, arrayOf(decInputIds)).use { decInputTensor ->
                            val decoderInputsMap = mutableMapOf<String, OnnxTensor>(
                                "input_ids" to decInputTensor,
                                "encoder_hidden_states" to encoderHidden
                            )
                            if (decoderWantsEncoderMask) {
                                decoderInputsMap["encoder_attention_mask"] = maskTensor
                            }
                            decoderSession.run(decoderInputsMap).use { decoderResult ->
                                val logitsTensor = decoderResult.get("logits")
                                    .orElseThrow {
                                        IllegalStateException(
                                            "디코더 출력에서 logits를 못 찾았어요. " +
                                                "실제 출력 이름: ${decoderSession.outputNames}"
                                        )
                                    } as OnnxTensor

                                @Suppress("UNCHECKED_CAST")
                                val logitsArr = logitsTensor.value as Array<Array<FloatArray>>
                                val lastPosition = logitsArr[0][logitsArr[0].size - 1]
                                var bestId = -1
                                var bestScore = Float.NEGATIVE_INFINITY
                                for (i in lastPosition.indices) {
                                    if (i in banned) continue
                                    if (lastPosition[i] > bestScore) {
                                        bestScore = lastPosition[i]
                                        bestId = i
                                    }
                                }
                                if (bestId == -1) {
                                    for (i in lastPosition.indices) {
                                        if (lastPosition[i] > bestScore) {
                                            bestScore = lastPosition[i]
                                            bestId = i
                                        }
                                    }
                                }
                                bestId.toLong()
                            }
                        }
                        generated.add(nextId)
                        steps++
                        if (nextId == EOS_ID) break
                    }

                    val outputIds = generated.drop(2).filter { it != EOS_ID }.toLongArray()
                    val resultText = tokenizer.decode(outputIds, true)
                    val elapsed = System.currentTimeMillis() - startTime
                    return TranslateResult(resultText, elapsed)
                }
            }
        }
    }

    override fun close() {
        encoderSession.close()
        decoderSession.close()
        tokenizer.close()
    }
}
