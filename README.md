# CQ — Android WebView Application

CQ is a modern, feature-packed Android wrapper application built with Kotlin. It wraps a web application (like the [CQ Demo](https://study-tanvirr007.vercel.app)) using a highly customized `WebView` that delivers a native-like experience, including dynamic system bar coloring, real-time background OTA updates, biometric app locking, and file download support.

---

## 🌟 Key Features

* **Customized WebView Engine**: Renders modern web apps seamlessly with JavaScript, DOM storage, cookies, blob downloads, and sub-resource asset loading (including external CDN images).
* **Real-time & Background OTA Updates**:
  * **Background WorkManager**: `OtaCheckWorker` checks for updates periodically in the background even when the app is completely closed.
  * **Real-time Push Stream**: `NtfyStreamListener` connects to `ntfy.sh` for instant real-time update notifications.
  * **In-App Download Manager**: Downloads update APKs directly inside the app with a live progress bar, speed (MB/s), percentage, and ETA.
* **Security & Privacy**:
  * **Biometric Lock**: Optional fingerprint/face unlock prompt when launching or resuming the app.
  * **Screen Privacy**: Toggleable `FLAG_SECURE` to prevent screenshots and task switcher previews.
* **Modern Splash Screen API**: Employs Android’s official Splash Screen API to keep the splash screen visible until the initial web page loads fully.
* **Dynamic System Bar Theme**: Automatically synchronizes status and navigation bar colors with the web app's theme state.
* **Native Downloads & Printing**: Native bridge for blob downloads, data URIs, and browser printing.
* **Automated CI/CD**: Automatic generation of signed release APKs on every commit to `master` via GitHub Actions.

---

## 📂 Key File Locations

* **Core UI & Activity**: [MainActivity.kt](app/src/main/java/study/tanvir/info/MainActivity.kt) — WebView setup, back navigation, permissions, theme sync, biometric lock.
* **OTA Update Engine**: [UpdateChecker.kt](app/src/main/java/study/tanvir/info/UpdateChecker.kt) — Version parsing, update dialog, in-app downloader, and system notifications.
* **Background Worker**: [OtaCheckWorker.kt](app/src/main/java/study/tanvir/info/OtaCheckWorker.kt) — Periodic background update checks via Android `WorkManager`.
* **Real-time Push**: [NtfyStreamListener.kt](app/src/main/java/study/tanvir/info/NtfyStreamListener.kt) — SSE listener for `ntfy.sh` real-time push events.
* **Gradle Build Settings**: [app/build.gradle.kts](app/build.gradle.kts) — Android build configuration and dependencies.
* **CI/CD Pipeline**: [.github/workflows/build_apk.yml](.github/workflows/build_apk.yml) — GitHub Actions automated build workflow.

---

## 🛠️ Local Setup & Development

### Prerequisites
* **Android Studio** (Koala or newer recommended)
* **JDK 17 or 21**

### Step-by-Step Setup
1. **Open the Project**: Launch Android Studio, select **File** -> **Open**, and pick the repository root directory. Gradle will sync automatically.
2. **Run on Device/Emulator**: Connect your target device via USB or start an emulator (AVD), then click **Run**.
3. **Build APKs locally**:
   ```bash
   # Build a debug APK for local testing
   ./gradlew assembleDebug

   # Build a release APK locally (requires keystore setup)
   ./gradlew assembleRelease
   ```

---

## 🚀 CI/CD & Signing Configuration

GitHub Actions automatically builds and signs release APKs on every push to the `master` branch.

### Configuring Signing Keys
To enable automatic release signing, configure the following secrets under **Settings** -> **Secrets and variables** -> **Actions**:

1. **Prepare Keystore**: Generate a standard release keystore `.jks` file.
2. **Convert Keystore to Base64**:
   * **Windows (PowerShell)**:
     ```powershell
     [Convert]::ToBase64String([IO.File]::ReadAllBytes("your-key.jks"))
     ```
   * **macOS / Linux**:
     ```bash
     openssl base64 -in your-key.jks -out keystore-base64.txt
     ```
3. **Add Repository Secrets**:
   * `KEYSTORE` - Base64 string of your keystore file.
   * `KEYSTORE_PASSWORD` - Password for your keystore.
   * `KEY_ALIAS` - Alias name of your key.
   * `KEY_PASSWORD` - Password for your key alias.

---

## 🔄 OTA Update Architecture

### How OTA Checking Works
1. **Manifest File**: The app checks `https://raw.githubusercontent.com/tanvirr007/study-releases/master/version.json` with cache-busting query params.
2. **Version Comparison**: The app compares the remote `versionCode` against the locally installed `versionCode`.
3. **Notification & Installation**:
   * **Background**: If a newer version is found, a native notification is posted to the status bar.
   * **In-App Flow**: Clicking **Update Now** downloads the `.apk` directly within the app, verifies package integrity, and triggers the Android Package Installer.
