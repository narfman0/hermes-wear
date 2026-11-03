package dev.narfman0.hermeswear

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.wearableExtender

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    /** Bumped when the tile's "Ask" opens the app, so the UI starts voice input. */
    private val voiceRequests = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent { HermesApp(vm, voiceRequests.intValue) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_VOICE, false) && vm.prefs.configured) voiceRequests.intValue++
        debugExtras(intent)
    }

    /**
     * Configuration and a test hook, both debug-only:
     *   adb shell am start -n dev.narfman0.hermeswear/.MainActivity \
     *       --es url http://host:8642 --es key SECRET --es say "hello"
     * Release builds ignore these extras so nothing else on the watch can set the key.
     */
    private fun debugExtras(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        intent.getStringExtra("url")?.let(vm::setUrl)
        intent.getStringExtra("key")?.let(vm::setKey)
        intent.getStringExtra("conversation")?.let { vm.prefs.conversation = it }
        if (intent.hasExtra("speak")) vm.setSpeak(intent.getBooleanExtra("speak", true))
        vm.refreshConfigured()
        intent.getStringExtra("say")?.let { vm.send(it) }
    }

    companion object {
        const val EXTRA_VOICE = "dev.narfman0.hermeswear.VOICE"
    }
}

private fun speechIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Hermes")
}

/** Wear's text entry screen (keyboard, voice, handwriting) for one field. */
private fun textInputIntent(label: String): Intent {
    val input = RemoteInput.Builder(RESULT_KEY)
        .setLabel(label)
        .wearableExtender {
            setEmojisAllowed(false)
            setInputActionType(EditorInfo.IME_ACTION_DONE)
        }
        .build()
    return RemoteInputIntentHelper.createActionRemoteInputIntent().also {
        RemoteInputIntentHelper.putRemoteInputsExtra(it, listOf(input))
        RemoteInputIntentHelper.putTitleExtra(it, label)
    }
}

private const val RESULT_KEY = "value"

@Composable
private fun rememberTextInput(onResult: (String) -> Unit): ManagedActivityResultLauncher<Intent, ActivityResult> =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        RemoteInput.getResultsFromIntent(data)?.getCharSequence(RESULT_KEY)?.toString()?.let(onResult)
    }

@Composable
fun HermesApp(vm: ChatViewModel, voiceRequests: Int) {
    val nav = rememberSwipeDismissableNavController()
    MaterialTheme {
        AppScaffold {
            SwipeDismissableNavHost(navController = nav, startDestination = "chat") {
                composable("chat") {
                    ChatScreen(vm, voiceRequests, onSettings = { nav.navigate("settings") })
                }
                composable("settings") { SettingsScreen(vm) }
            }
        }
    }
}

@Composable
private fun ChatScreen(vm: ChatViewModel, voiceRequests: Int, onSettings: () -> Unit) {
    val state by vm.state.collectAsState()
    val listState = rememberScalingLazyListState()

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(vm::send)
        }
    }

    // Opened from the tile's Ask button: go straight to voice input, once per request.
    val handled = remember { mutableIntStateOf(0) }
    LaunchedEffect(voiceRequests) {
        if (voiceRequests > handled.intValue) {
            handled.intValue = voiceRequests
            if (!vm.state.value.busy) speech.launch(speechIntent())
        }
    }

    // Keep the newest text on screen while the reply streams in.
    LaunchedEffect(state.lines.size, state.lines.lastOrNull()?.text?.length, state.error) {
        // Scroll to the true end (not just the last item centred), so the edge button is full size.
        if (state.lines.isNotEmpty()) listState.scrollBy(100_000f)
    }

    ScreenScaffold(
        scrollState = listState,
        // Pinned to the bottom edge, so Talk/Stop stays reachable while a long reply streams.
        edgeButton = {
            when {
                !state.configured -> EdgeButton(onClick = onSettings) { Text("Set up") }
                state.busy -> EdgeButton(
                    onClick = vm::stop,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                ) { Text("Stop") }
                else -> EdgeButton(onClick = { speech.launch(speechIntent()) }) { Text("Talk") }
            }
        },
    ) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.configured) {
                item {
                    CompactButton(onClick = onSettings, label = { Text("Settings") })
                }
            }
            if (state.lines.isEmpty()) {
                item {
                    Text(
                        if (state.configured) "Tap Talk to ask Hermes." else "Set the server first.",
                        textAlign = TextAlign.Center,
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

@Composable
private fun SettingsScreen(vm: ChatViewModel) {
    val state by vm.state.collectAsState()
    val s = state.settings
    val listState = rememberScalingLazyListState()
    val urlInput = rememberTextInput(vm::setUrl)
    val keyInput = rememberTextInput(vm::setKey)

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Settings") } }
            item {
                FilledTonalButton(
                    onClick = { urlInput.launch(textInputIntent("Server URL")) },
                    label = { Text("Server") },
                    secondaryLabel = {
                        Text(s.url.ifEmpty { "Not set" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                FilledTonalButton(
                    onClick = { keyInput.launch(textInputIntent("API key")) },
                    label = { Text("API key") },
                    secondaryLabel = { Text(if (s.keySet) "Set" else "Not set") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Button(
                    onClick = vm::checkServer,
                    enabled = state.configured && !s.checking,
                    label = { Text(if (s.checking) "Checking…" else "Test connection") },
                    secondaryLabel = s.check?.let { msg -> { Text(msg) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                SwitchButton(
                    checked = s.speak,
                    onCheckedChange = vm::setSpeak,
                    label = { Text("Speak replies") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedButton(
                    onClick = vm::newConversation,
                    label = { Text("New conversation") },
                    secondaryLabel = { Text(s.conversation, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    "The key gives full agent access. Use your LAN or a VPN.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
