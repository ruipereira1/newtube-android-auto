# Contributing

This repository is the **Android Auto fork** of NewTube. Upstream NewTube changes belong in
[aleixrodriala/newtube](https://github.com/aleixrodriala/newtube); changes to the phone UI,
player or offline support should be sent there first.

## Safety rule

Video on the car screen is for **passengers only**. Do not add a feature whose purpose is to let
the driver watch video while driving. Keep the passenger-mode switch working and keep it visible
from the car screen.

## Build and test

```bash
git clone --recurse-submodules https://github.com/ruipereira1/newtube-android-auto.git
cd newtube-android-auto
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew :smarttubetv:assembleStmobileDebug
./gradlew :smarttubetv:testStmobileDebugUnitTest \
  --tests 'com.newtube.mobile.car.CarModePrefsTest'
```

Requirements: JDK 17+ (tested with 21), Android SDK 37, and the `MediaServiceCore` and
`SharedModules` submodules. See [docs/BUILDING.md](docs/BUILDING.md) and
[docs/ANDROID_AUTO.md](docs/ANDROID_AUTO.md).

## Pull requests

- Keep the change focused. One feature or fix per pull request.
- Add or update a test when the change touches behaviour.
- If you change the car integration, update `docs/ANDROID_AUTO.md` and
  `docs/ANDROID_AUTO.pt.md`.
- Say which phone, Android version, car and Android Auto version you tested with.
- Do not commit keystores, `keystore.properties`, `local.properties`, build outputs or logs.
- Do not add a second player. The car screen must keep following the phone's live player.

## Code map

The Android Auto integration is deliberately small:

```text
smarttubetv/src/stmobile/java/com/newtube/mobile/car/
smarttubetv/src/stmobile/java/com/newtube/mobile/ui/playback/CarDisplayBridge.java
smarttubetv/src/stmobile/java/com/newtube/mobile/ui/playback/MobilePlaybackActivity.java
smarttubetv/src/stmobile/AndroidManifest.xml
smarttubetv/src/stmobile/res/xml/automotive_app_desc.xml
smarttubetv/src/testStmobile/java/com/newtube/mobile/car/CarModePrefsTest.java
```

## License

By contributing you agree that your changes are released under the repository's
[MIT license](LICENSE).
