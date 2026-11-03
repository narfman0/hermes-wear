package dev.narfman0.hermeswear

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatLine(val fromUser: Boolean, val text: String)

data class SettingsState(
    val url: String = "",
    val keySet: Boolean = false,
    val conversation: String = Prefs.DEFAULT_CONVERSATION,
    val speak: Boolean = true,
    val check: String? = null,
    val checking: Boolean = false,
)

data class ChatState(
    val lines: List<ChatLine> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val configured: Boolean = false,
    val settings: SettingsState = SettingsState(),
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    private val client = HermesClient(prefs)
    private val speaker = Speaker(app)
    private val chunker = SpeechChunker()
    private var job: Job? = null

    private val _state = MutableStateFlow(fresh(restoredLines()))
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private fun fresh(lines: List<ChatLine>) =
        ChatState(lines = lines, configured = prefs.configured, settings = settingsFromPrefs())

    /** Show the last exchange again after the app was closed, so the tile and the app agree. */
    private fun restoredLines(): List<ChatLine> =
        if (prefs.lastAsk.isBlank()) emptyList()
        else listOf(ChatLine(true, prefs.lastAsk), ChatLine(false, prefs.lastReply))

    private fun settingsFromPrefs(check: String? = null) = SettingsState(
        url = prefs.baseUrl,
        keySet = prefs.apiKey.isNotEmpty(),
        conversation = prefs.conversation,
        speak = prefs.speakReplies,
        check = check,
    )

    private fun refreshSettings(check: String? = _state.value.settings.check) =
        _state.update { it.copy(configured = prefs.configured, settings = settingsFromPrefs(check)) }

    fun refreshConfigured() = refreshSettings()

    fun setUrl(v: String) {
        prefs.baseUrl = v
        refreshSettings(check = null)
        HermesTileService.requestUpdate(getApplication())
    }

    fun setKey(v: String) {
        prefs.apiKey = v
        refreshSettings(check = null)
        HermesTileService.requestUpdate(getApplication())
    }

    fun setSpeak(v: Boolean) {
        prefs.speakReplies = v
        if (!v) speaker.stop()
        refreshSettings()
    }

    /** Start a fresh server-side thread by switching to a new conversation name. */
    fun newConversation() {
        stop()
        prefs.conversation = "${Prefs.DEFAULT_CONVERSATION}-${System.currentTimeMillis() / 1000}"
        prefs.lastAsk = ""
        prefs.lastReply = ""
        _state.value = fresh(emptyList())
        HermesTileService.requestUpdate(getApplication())
    }

    fun checkServer() {
        _state.update { it.copy(settings = it.settings.copy(checking = true, check = null)) }
        viewModelScope.launch {
            val result = try {
                client.check()
            } catch (e: HermesException) {
                e.message ?: "Failed"
            }
            _state.update { it.copy(settings = it.settings.copy(checking = false, check = result)) }
        }
    }

    fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || _state.value.busy) return
        speaker.stop()
        chunker.reset()
        _state.update {
            it.copy(
                lines = it.lines + ChatLine(true, clean) + ChatLine(false, ""),
                busy = true,
                error = null,
            )
        }
        prefs.lastAsk = clean
        prefs.lastReply = ""
        val speak = prefs.speakReplies
        job = viewModelScope.launch {
            try {
                client.ask(clean).collect { delta ->
                    appendToReply(delta)
                    if (speak) chunker.feed(delta).forEach(speaker::speak)
                }
                if (speak) chunker.flush().forEach(speaker::speak)
            } catch (e: HermesException) {
                _state.update { it.copy(error = e.message) }
            } finally {
                prefs.lastReply = _state.value.lines.lastOrNull()?.takeIf { !it.fromUser }?.text.orEmpty()
                _state.update { it.copy(busy = false) }
                HermesTileService.requestUpdate(getApplication())
            }
        }
    }

    /** Stop streaming and talking. The server may still finish the turn on its side. */
    fun stop() {
        job?.cancel()
        speaker.stop()
        chunker.reset()
    }

    private fun appendToReply(delta: String) = _state.update { s ->
        val last = s.lines.lastOrNull()
        if (last == null || last.fromUser) s
        else s.copy(lines = s.lines.dropLast(1) + last.copy(text = last.text + delta))
    }

    override fun onCleared() {
        speaker.shutdown()
    }
}
