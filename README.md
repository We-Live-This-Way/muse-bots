# WLTW Bots

WLTW Bots is one Android app for talking to the bots you already run. Chat with agents on a Rakazo server or an OpenMausBot server, and join rooms on a self-hosted [bot-hub](https://github.com/We-Live-This-Way/bot-hub) — without switching between three different clients.

This tree is **v1.0** (`versionName` 1.0, `versionCode` 1).

## What you need

- An Android phone running Android 7.0 or newer
- The base URL and credentials for each service you want to use: a Rakazo server, an OpenMausBot server, and/or a [bot-hub](https://github.com/We-Live-This-Way/bot-hub)

You can fill in just the services you use. Nothing is hard-coded.

## Install

1. Download `WLTW-Bots-1.0.apk` from the [latest release](https://github.com/We-Live-This-Way/muse-bots/releases/latest).
2. On the phone, open the file. If Android asks, allow installs from your browser or file manager, then tap Install.
3. Open **WLTW Bots**, go to **Settings**, and enter the address and credentials for each service.

If you already installed an older test or debug build, uninstall it first. This release is signed with a new key, so it cannot update the old app in place. You will need to re-enter server addresses and tokens.

Tokens stay in the app's private storage. No credentials are baked into the source.

## Set up

In Settings, enter the base URL and credentials for each service you use (Rakazo, OpenMausBot, and/or the hub). API notes for the two bot servers are in `api-rakazo.md` and `api-openmausbot.md`.

## Build from source

The Gradle project lives in `app/`. On a machine with JDK 17 and the Android SDK (compileSdk 34, minSdk 24):

```bash
cd app
gradle assembleDebug
```

A command-line script is also included:

```bash
cd app
./build-apk-manual.sh
```

The script compiles with `aapt2`, `javac`, `kotlinc`, `d8`, `zipalign`, and `apksigner`. It expects a local Android SDK (`$HOME/Android/Sdk`) and may need its JDK / Kotlin paths adjusted. A copy of `kotlin-stdlib` is in `app/libs/` for the offline build. Official release APKs on GitHub are signed with a dedicated release key kept off this repository. If you build your own APK with a different key, it will not install over the official release (uninstall first).
