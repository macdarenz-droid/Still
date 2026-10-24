# Quiet Home

A small Android home launcher study. It reads the apps Android exposes in the launcher list. App names, icons, and favourite choices stay on the device.

## Try it

Open this folder in Android Studio with Java 17 and Android SDK 35. The included Gradle Wrapper downloads the pinned Gradle version. To build a debug APK from a terminal, run `./gradlew assembleDebug` (or `gradlew.bat assembleDebug` on Windows). When Android asks which Home app to use, choose **Quiet Home**. You can change the default later under **Settings → Apps → Default apps → Home app**.

Touch and hold the centre circle, then move toward an app. The apps expand from the circle and the one under your finger grows. Release over it to fold the fan back into the circle and open the app. Tapping an app opens it directly. Grid and List use the full screen; choose either in Settings. Favourite apps appear first in Orbit, and the Settings screen also lets you choose a theme and wallpaper.

This is a launcher app, so it changes the home screen after you choose it as Home. It does not replace Android's lock screen, notification shade, or system settings.
