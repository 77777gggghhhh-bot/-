package com.yourapp.translatebubble

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TranslatorHelper {

    private val arToEn: Translator by lazy {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(com.google.mlkit.nl.translate.TranslateLanguage.ARABIC)
            .setTargetLanguage(com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH)
            .build()
        Translation.getClient(options)
    }

    private val enToAr: Translator by lazy {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH)
            .setTargetLanguage(com.google.mlkit.nl.translate.TranslateLanguage.ARABIC)
            .build()
        Translation.getClient(options)
    }

    suspend fun translate(text: String, arabicToEnglish: Boolean): Result<String> {
        val translator = if (arabicToEnglish) arToEn else enToAr
        return try {
            ensureModelDownloaded(translator)
            val translated = runTranslate(translator, text)
            Result.success(translated)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun ensureModelDownloaded(translator: Translator) =
        suspendCancellableCoroutine<Unit> { cont ->
            val conditions = DownloadConditions.Builder()
                .requireWifi()
                .build()
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener { cont.resume(Unit) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }

    private suspend fun runTranslate(translator: Translator, text: String): String =
        suspendCancellableCoroutine { cont ->
            translator.translate(text)
                .addOnSuccessListener { translatedText -> cont.resume(translatedText) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }

    fun looksArabic(text: String): Boolean {
        return text.any { it.code in 0x0600..0x06FF }
    }

    fun close() {
        arToEn.close()
        enToAr.close()
    }
}
