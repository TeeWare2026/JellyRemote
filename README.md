# JellyRemote

JellyRemote is a native Android remote for any controllable playback session on a Jellyfin server.

## Install and use

1. Download the JellyRemote APK from the repository’s **Releases** page, then install it on a phone running Android 8.0 or newer. Android may ask you to allow installs from the app used to open the APK.
2. Enter the full address of your Jellyfin server, including any configured port or base path.
3. Sign in with your Jellyfin username and password.
4. Start a movie or episode in any Jellyfin client that supports remote control.
5. JellyRemote automatically selects the active controllable session. Tap the device panel at the top to cycle when several sessions are available.

The remote includes play/pause, stop, previous/next episode, ten-second rewind/forward, timeline seeking, chapter selection, mute, volume, and subtitle on/off. The chapter control appears only when the current movie or episode has chapter markers.

Artwork is fetched from the Jellyfin server and sampled locally into a coordinated two-accent palette. Glass panels, buttons, progress tracks, status treatments, and ambient lighting adapt to the title while maintaining bright text and dark surfaces for readable contrast.

## Privacy and networking

- The password is sent only to the configured Jellyfin server and is never saved.
- The returned access token is kept in Android's app-private preferences. Use **Disconnect server** to erase it.
- Plain HTTP is enabled because local Jellyfin servers commonly use port 8096. Prefer HTTPS or a VPN when connecting outside the home network; do not expose port 8096 directly to the internet.
- The receiving Jellyfin client must advertise remote-control support, and the signed-in user must be allowed to control that session.

## Build

The project uses Android Gradle Plugin 8.7.3, compile SDK 35, and Java 17 language features.

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease
```

The personal sideload APK is written to `app/build/outputs/apk/release/app-release.apk`. It is a non-debuggable release build signed with the local Android development key; use a private release keystore before wider distribution.

## API compatibility

The implementation uses Jellyfin's stable session endpoints: `GET /Sessions`, play-state commands under `/Sessions/{id}/Playing/{command}`, and general commands under `/Sessions/{id}/Command`. Subtitle discovery supports both the current item endpoint and the older Jellyfin 10.x user-item route.
