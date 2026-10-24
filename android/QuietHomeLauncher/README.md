# Quiet Home

A small Android home launcher study. It reads the apps Android exposes in the launcher list. App names, icons, and favourite choices stay on the device.

## Try it

Open this folder in Android Studio with Java 17 and Android SDK 35. The included Gradle Wrapper downloads the pinned Gradle version. To build a debug APK from a terminal, run `./gradlew assembleDebug` (or `gradlew.bat assembleDebug` on Windows). When Android asks which Home app to use, choose **Quiet Home**. You can change the default later under **Settings → Apps → Default apps → Home app**.

Hold the centre circle and drag over an app. The app under your finger grows; release to select it. Tap **Open** to launch it. Orbit shows up to five favourites and uses fewer in a narrow split-screen window. Grid and List show installed launcher apps, with a star to change favourites.

This is a launcher app, so it changes the home screen after you choose it as Home. It does not replace Android's lock screen, notification shade, or system settings.
