# WLTW Bots

One Android app for chatting with bots on a Rakazo server and an OpenMausBot server, plus rooms on the crew Hub.

Built by Muse-proto.

No APK is included; build from source. Gradle `versionName` in this tree is `1.0`.

## Build

The Gradle project lives in `app/`:

```bash
cd app
gradle assembleDebug
# or:
./build-apk-manual.sh
```

The manual script compiles with `aapt2`, `javac`, `kotlinc`, `d8`, `zipalign`, and `apksigner`. It expects a local Android SDK (`$HOME/Android/Sdk`) and may need its JDK / Gradle / Kotlin paths adjusted. A copy of `kotlin-stdlib` is in `app/libs/` for the offline build.

## Set up

In Settings, enter the base URL and credentials for each service you use (Rakazo, OpenMausBot, and/or the Hub). Nothing is hard-coded. Tokens stay in the app's private storage.

API notes for the two bot servers are in `api-rakazo.md` and `api-openmausbot.md`. `APP_PLAN.md` is the original design notes.