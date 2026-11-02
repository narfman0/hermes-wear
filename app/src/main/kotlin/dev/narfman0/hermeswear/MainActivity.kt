package dev.narfman0.hermeswear

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent { HermesApp(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * Configuration and a test hook, both debug-only:
     *   adb shell am start -n dev.narfman0.hermeswear/.MainActivity \
     *       --es url http://host:8642 --es key SECRET --es say "hello"
     * Release builds ignore these extras so nothing else on the watch can set the key.
     */
    private fun handleIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        intent.getStringExtra("url")?.let { vm.prefs.baseUrl = it }
        intent.getStringExtra("key")?.let { vm.prefs.apiKey = it }
        intent.getStringExtra("conversation")?.let { vm.prefs.conversation = it }
        vm.refreshConfigured()
        intent.getStringExtra("say")?.let { vm.send(it) }
    }
}

@Composable
fun HermesApp(vm: ChatViewModel) {
    val state by vm.state.collectAsState()
    val listState = rememberScalingLazyListState()

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(vm::send)
        }
    }

    // Keep the newest text on screen while the reply streams in.
    LaunchedEffect(state.lines.size, state.lines.lastOrNull()?.text?.length) {
        if (state.lines.isNotEmpty()) listState.scrollToItem(state.lines.size)
    }

    MaterialTheme {
        AppScaffold {
            ScreenScaffold(scrollState = listState) {
                ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                    item {
                        Button(
                            onClick = {
                                speech.launch(
                                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(
                                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                        )
                                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Hermes")
                                    },
                                )
                            },
                            enabled = !state.busy && state.configured,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                when {
                                    !state.configured -> "Not set up"
                                    state.busy -> "Thinking…"
                                    else -> "Talk"
                                },
                            )
                        }
                    }
                    items(state.lines.size) { i ->
                        val line = state.lines[i]
                        Text(
                            text = if (line.fromUser) "You: ${line.text}" else line.text.ifEmpty { "…" },
                            color = if (line.fromUser) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        )
                    }
                    state.error?.let { err ->
                        item { Text("Error: $err", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}
