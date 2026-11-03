package dev.narfman0.hermeswear

import android.content.Context

/** Settings and the last exchange, stored on the watch. */
class Prefs(context: Context) : ServerConfig {
    private val sp = context.applicationContext.getSharedPreferences("hermes", Context.MODE_PRIVATE)

    override var baseUrl: String
        get() = sp.getString("url", "") ?: ""
        set(v) = sp.edit().putString("url", normalizeUrl(v)).apply()

    override var apiKey: String
        get() = sp.getString("key", "") ?: ""
        set(v) = sp.edit().putString("key", v.trim()).apply()

    /** Server-side conversation name, so the watch keeps one running thread. */
    override var conversation: String
        get() = sp.getString("conversation", DEFAULT_CONVERSATION) ?: DEFAULT_CONVERSATION
        set(v) = sp.edit().putString("conversation", v.trim().ifEmpty { DEFAULT_CONVERSATION }).apply()

    var speakReplies: Boolean
        get() = sp.getBoolean("speak", true)
        set(v) = sp.edit().putBoolean("speak", v).apply()

    /** Shown on the tile. */
    var lastAsk: String
        get() = sp.getString("lastAsk", "") ?: ""
        set(v) = sp.edit().putString("lastAsk", v).apply()

    var lastReply: String
        get() = sp.getString("lastReply", "") ?: ""
        set(v) = sp.edit().putString("lastReply", v).apply()

    companion object {
        const val DEFAULT_CONVERSATION = "pixel-watch"

        /** Accepts "host:8642" or a full URL; adds http:// and drops a trailing slash. */
        fun normalizeUrl(raw: String): String {
            val t = raw.trim().trimEnd('/')
            if (t.isEmpty()) return ""
            return if (t.startsWith("http://") || t.startsWith("https://")) t else "http://$t"
        }
    }
}
