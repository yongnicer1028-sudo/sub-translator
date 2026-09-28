package com.yongyong.subtranslator

import android.content.Context

/**
 * API 키와 언어 설정을 폰에 저장해두는 곳.
 * MainActivity(설정 화면)와 TranslateOverlayService(실제 번역 작업) 둘 다 여기서 값을 읽어요.
 */
object Prefs {
    private const val PREFS_NAME = "sub_translator_prefs"
    private const val KEY_LANGUAGE = "source_language"

    const val LANG_CHINESE = "zh"
    const val LANG_JAPANESE = "ja"
    const val LANG_ENGLISH = "en"
    const val LANG_RUSSIAN = "ru"
    const val LANG_GERMAN = "de"

    fun getLanguage(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, LANG_CHINESE) ?: LANG_CHINESE

    fun setLanguage(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, value).apply()
    }

    // ⚠ v2: 음성인식 "정확도 우선 모드"(용량 큰 1~2GB 모델을 선택할 수 있게 하던 설정)를
    // 여기 있던 getAsrHighAccuracy/setAsrHighAccuracy와 함께 완전히 없앴어요. 번역
    // 모델이랑 같이 메모리에 떠 있으면 기기에 따라 실행 중 꺼지는 문제가 있었는데,
    // 껐다 켰다 하는 옵션으로 두는 것보다 아예 없애는 게 더 안전해서예요. 번역 품질은
    // 대신 VoskSpeechClient.kt의 단어 신뢰도 필터링으로 개선하고 있어요.

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
