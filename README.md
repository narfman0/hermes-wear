# hermes-wear

Wear OS app that talks to a Hermes Agent API server (`POST /v1/responses`, streamed).
Voice or keyboard in, streamed text out (optionally spoken), one named server-side conversation.

- **App:** Talk (edge button) opens the watch's voice/keyboard input. The reply streams in; Stop cancels it.
- **Settings** (on the watch): server URL (`host:8642` is fine, `http://` is added), API key,
  Test connection (`GET /v1/models`), Speak replies on/off, New conversation.
- **Tile:** shows the last reply. Tap the text to open the app; **Ask** opens straight into voice input.
- **Spoken replies:** sentences go to the watch's text-to-speech engine as they arrive,
  with markdown, links and code blocks cleaned up first.

## Build and test
Needs JDK 21 and the Android SDK (platform 37, build-tools 36).

    ./gradlew :app:testDebugUnitTest :app:assembleDebug

## Try it without Hermes
    python3 tools/fake_hermes.py --port 18642 --key testkey [--delay 0.5]
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    adb shell am start -n dev.narfman0.hermeswear/.MainActivity \
        --es url http://10.0.2.2:18642 --es key testkey --es say "hello"

`10.0.2.2` is the host as seen from the emulator. On a real watch use the server's LAN address.
Debug builds accept `url`, `key`, `conversation`, `say` (`--es`) and `speak` (`--ez`) intent extras;
release builds ignore them. Settings can also be entered on the watch.

Add the tile on the emulator, then swipe left from the watch face:

    adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE \
        --es operation add-tile --ecn component dev.narfman0.hermeswear/.HermesTileService

The Wear emulator image has no TTS engine. To hear replies, install eSpeak NG from F-Droid
(`com.reecedunn.espeak`), open it once in the foreground so it unpacks its voices, and run
`adb shell settings put secure tts_default_synth com.reecedunn.espeak`. Real watches ship Google TTS.

## Real server
Hermes needs `API_SERVER_ENABLED=true`, `API_SERVER_HOST`, `API_SERVER_PORT` and `API_SERVER_KEY`
in `~/.hermes/.env`, and a gateway restart. The key gives full agent access. Treat it like a password,
and keep the port on the LAN or a VPN.

## Not done yet
A physical-watch test (speech input, Google TTS, the tile on a real launcher), use away from home
(Tailscale or similar), and release signing.
