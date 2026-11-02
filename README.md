# hermes-wear

Wear OS app that talks to a Hermes Agent API server (`POST /v1/responses`, streamed).
Proof of concept: voice or keyboard in, streamed text out, one named server-side conversation.

## Build and test
Needs JDK 21 and the Android SDK (platform 37, build-tools 36).

    ./gradlew :app:testDebugUnitTest :app:assembleDebug

## Try it without Hermes
    python3 tools/fake_hermes.py --port 18642 --key testkey
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    adb shell am start -n dev.narfman0.hermeswear/.MainActivity \
        --es url http://10.0.2.2:18642 --es key testkey --es say "hello"

`10.0.2.2` is the host as seen from the emulator. On a real watch use the server's LAN address.
Debug builds accept `url`, `key`, `conversation` and `say` intent extras; release builds ignore them.

## Real server
Hermes needs `API_SERVER_ENABLED=true` and `API_SERVER_KEY` in `~/.hermes/.env`, and a gateway restart.
The key gives full agent access. Treat it like a password.

## Not done yet
Tile, settings screen on the watch, spoken replies, the real server run, a physical-watch test.
