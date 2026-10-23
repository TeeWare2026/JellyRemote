# JellyRemote demo assets

These screenshots show the app with fictional titles, a fictional receiving device, and original geometric sample artwork. No personal server address, credentials, account data, or viewing history is used.

The animated tour moves through captured app screens. It demonstrates the controls, movie library, series, seasons, episodes, and resume options. It does not claim to record a live playback session.

To regenerate the source captures on a connected Android device with the debug and instrumentation APKs installed:

```sh
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e promotion true \
  com.signalbox.jellyremote.test/com.signalbox.jellyremote.LibrarySmokeTest
```

The captures are written to the app's external-files `ui-checks` directory. The demo catalog listens only on device loopback and records no playback commands. Demo fixtures belong to the instrumentation APK and are not included in the release APK.

After copying those captures to your computer, render the gallery, looping GIF, and 1280 × 720 sharing card with FFmpeg:

```sh
bash scripts/build-demo.sh /path/to/ui-checks
```

The sharing-card renderer uses the DejaVu Sans fonts from `/usr/share/fonts/truetype/dejavu/`.
