# JellyRemote demo assets

App screenshots use fictional titles and original geometric artwork from the instrumentation demo catalog.

The animated tour combines captures of the remote, movie library, series, seasons, episodes, and resume screen.

To regenerate the source captures on a connected Android device with the debug and instrumentation APKs installed:

```sh
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e promotion true \
  com.signalbox.jellyremote.test/com.signalbox.jellyremote.LibrarySmokeTest
```

The captures are written to the app's external-files `ui-checks` directory. The demo catalog listens only on device loopback and records no playback commands. Demo fixtures belong to the instrumentation APK and are not included in the release APK.

Render the copied captures into the gallery, looping GIF, and 1280 × 720 sharing card with FFmpeg:

```sh
bash scripts/build-demo.sh /path/to/ui-checks
```

The sharing-card renderer uses the DejaVu Sans fonts from `/usr/share/fonts/truetype/dejavu/`.
