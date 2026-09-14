package com.yourapp.translatebubble

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    // ---------------------------------------------------------------------
    // Translate many blocks at once, a few at a time in parallel, instead
    // of one-by-one. Much faster when a screen has lots of text blocks.
    // The model only needs to download once (first call); the rest reuse
    // the cached model instantly.
    // ---------------------------------------------------------------------
    suspend fun translateBatch(
        texts: List<String>,
        arabicToEnglish: Boolean,
        maxConcurrent: Int = 4
    ): List<Result<String>> = coroutineScope {
        // Make sure the model is ready before firing off concurrent calls.
        val translator = if (arabicToEnglish) arToEn else enToAr
        ensureModelDownloaded(translator)

        val semaphore = Semaphore(maxConcurrent)
        texts.map { text ->
            async {
                semaphore.withPermit {
                    translate(text, arabicToEnglish)
                }
            }
        }.awaitAll()
    }

    private suspend fun ensureModelDownloaded(translator: Translator) =
        suspendCancellableCoroutine<Unit> { cont ->
            // No longer requires Wi-Fi: on mobile data the model still
            // downloads (once, then cached), instead of silently failing.
            val conditions = DownloadConditions.Builder().build()
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
