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

data class ChatState(
    val lines: List<ChatLine> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val configured: Boolean = false,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    private val client = HermesClient(prefs)
    private var job: Job? = null

    private val _state = MutableStateFlow(ChatState(configured = prefs.configured))
    val state: StateFlow<ChatState> = _state.asStateFlow()

    fun refreshConfigured() = _state.update { it.copy(configured = prefs.configured) }

    fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || _state.value.busy) return
        _state.update {
            it.copy(
                lines = it.lines + ChatLine(true, clean) + ChatLine(false, ""),
                busy = true,
                error = null,
            )
        }
        job = viewModelScope.launch {
            try {
                client.ask(clean).collect { delta -> appendToReply(delta) }
            } catch (e: HermesException) {
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun clear() {
        job?.cancel()
        _state.update { ChatState(configured = prefs.configured) }
    }

    private fun appendToReply(delta: String) = _state.update { s ->
        val last = s.lines.lastOrNull()
        if (last == null || last.fromUser) s
        else s.copy(lines = s.lines.dropLast(1) + last.copy(text = last.text + delta))
    }
}
