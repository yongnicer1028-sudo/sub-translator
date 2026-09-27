package com.yongyong.subtranslator

import android.content.Context

/**
 * API 키와 언어 설정을 폰에 저장해두는 곳.
 * MainActivity(설정 화면)와 TranslateOverlayService(실제 번역 작업) 둘 다 여기서 값을 읽어요.
 */
object Prefs {
    private const val PREFS_NAME = "sub_translator_prefs"
    private const val KEY_LANGUAGE = "source_language"
    private const val KEY_ASR_HIGH_ACCURACY = "asr_high_accuracy"

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

    // 음성인식(Vosk) 정확도 우선 모드 - 기본은 꺼짐(작은 모델, 안전 우선). 켜면 훨씬
    // 정확하지만 용량이 큰(약 1~2GB) 모델을 대신 받아서 써요. 번역 모델이랑 같이
    // 메모리에 올라가면 기기에 따라 실행 중 꺼질 수 있어서, 기본값은 꺼짐으로 두고
    // 원하는 사람만 설정 화면에서 켜게 했어요.
    fun getAsrHighAccuracy(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ASR_HIGH_ACCURACY, false)

    fun setAsrHighAccuracy(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ASR_HIGH_ACCURACY, value).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
