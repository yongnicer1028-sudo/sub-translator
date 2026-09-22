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

    fun getLanguage(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, LANG_CHINESE) ?: LANG_CHINESE

    fun setLanguage(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, value).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
