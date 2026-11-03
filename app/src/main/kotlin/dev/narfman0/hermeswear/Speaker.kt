package dev.narfman0.hermeswear

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * Speaks reply sentences in order with the watch's text-to-speech engine.
 * Sentences queued before the engine is ready are held and spoken once it is.
 */
class Speaker(context: Context) {
    private val held = mutableListOf<String>()
    private var ready = false
    private var failed = false

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            val lang = tts.setLanguage(Locale.getDefault())
            Log.i(TAG, "TextToSpeech ready (engine=${tts.defaultEngine}, language result=$lang)")
            ready = true
            held.forEach(::enqueue)
            held.clear()
        } else {
            failed = true
            held.clear()
            Log.w(TAG, "TextToSpeech init failed: $status")
        }
    }

    fun speak(sentence: String) {
        when {
            failed -> Unit
            ready -> enqueue(sentence)
            else -> held += sentence
        }
    }

    fun stop() {
        held.clear()
        if (ready) tts.stop()
    }

    fun shutdown() {
        held.clear()
        tts.shutdown()
    }

    private fun enqueue(sentence: String) {
        Log.d(TAG, "speak: $sentence")
        tts.speak(sentence, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
    }

    private companion object {
        const val TAG = "HermesSpeaker"
    }
}
