# CQ — Android WebView Application

CQ is a modern, lightweight Android wrapper application built with Kotlin. It wraps a web application (like the [CQ Demo](https://study-tanvirr007.vercel.app)) using a customized `WebView` that delivers a native-like experience, including dynamic system bar coloring, modern splash screen integration, and native navigation control.

---

## Key Features

* **WebView Container**: A highly customized `WebView` that renders web apps seamlessly with full JavaScript and client-side integration.
* **Modern Splash Screen API**: Employs Android’s official Splash Screen API to keep the splash screen visible until the initial web page loads fully, avoiding raw white/blank screens.
* **Dynamic System Bar Theme**: Automatically styles the Android status and navigation bars dynamically based on the active page URL to match the web app's theme color (e.g., light theme on home page, dark theme on internal dashboards).
* **Native Back Navigation**: Intercepts back gestures/presses to navigate back in the `WebView`'s history rather than closing the app immediately.
* **Automated CI/CD**: Automatic generation of signed release APKs on every commit to `master` via GitHub Actions.

---

## Key File Locations

* **Core Logic**: [MainActivity.kt](app/src/main/java/study/tanvir/info/MainActivity.kt) — Handles WebView configurations, splash screen, and dynamic bar coloring.
* **Gradle Configuration**: [app/build.gradle.kts](app/build.gradle.kts) — Android build settings and dependencies.
* **CI/CD Workflow**: [.github/workflows/build_apk.yml](.github/workflows/build_apk.yml) — GitHub Actions pipeline config.

---

## Local Setup & Development

### Prerequisites
* **Android Studio** (Koala or newer recommended)
* **JDK 17 or 21**

### Step-by-Step Setup
1. **Open the Project**: Launch Android Studio, select **File** -> **Open**, and pick the repository root directory. Gradle will sync automatically.
2. **Run on Device/Emulator**: Connect your target device via USB or start a virtual device (AVD), then click the green **Run** button.
3. **Build APKs locally**:
   ```bash
   # Build a debug build for local testing
   ./gradlew assembleDebug

   # Build a release build locally (requires keystore.properties setup)
   ./gradlew assembleRelease
   ```

---

## CI/CD & Signing Configuration

GitHub Actions automatically builds and signs release APKs on every push to the `master` branch.

### Configuring Signing Keys
To enable automatic release signing, configure the following secrets in your GitHub Repository under **Settings** -> **Secrets and variables** -> **Actions**:

1. **Prepare Keystore**: Generate a standard release keystore `.jks` file.
2. **Convert Keystore to Base64**:
   * **Windows (PowerShell)**:
     ```powershell
     [Convert]::ToBase64String([IO.File]::ReadAllBytes("your-key.jks"))
     ```
   * **macOS / Linux (Terminal)**:
     ```bash
     openssl base64 -in your-key.jks -out keystore-base64.txt
     ```
3. **Add Repository Secrets**:
   * `KEYSTORE` - The Base64 string of your keystore file.
   * `KEYSTORE_PASSWORD` - The password to your keystore.
   * `KEY_ALIAS` - The alias name of your key.
   * `KEY_PASSWORD` - The password to your key alias.

When the workflow runs, it recreates the keystore dynamically and builds the production-ready APK.

---

## Versioning & Upgrade Policy

### How Versioning Works
* **Base Version**: Defined locally in [app/build.gradle.kts](app/build.gradle.kts) via the `versionName` property (e.g., `"1.0"`).
* **CI/CD Builds**: The GitHub Actions pipeline dynamically injects and updates these parameters prior to compilation:
  * **`versionCode`**: Map to the GitHub Actions run number (`${{ github.run_number }}`). This is a strictly incrementing integer required by Android to determine if a build is newer.
  * **`versionName`**: Constructed dynamically by combining the local base version and the run number: `<base_version_name>.<run_number>` (e.g., `1.0.12`).

### How to Bump the Version (Step-by-Step)
1. Open [app/build.gradle.kts](app/build.gradle.kts).
2. Locate the `versionName` variable in the `defaultConfig` block (around line 20):
   ```kotlin
   versionName = "1.0"
   ```
3. Change `"1.0"` to your new desired base version (e.g., `"1.1"` or `"2.0"`).
4. Stage, commit, and push the change to GitHub:
   ```bash
   git add app/build.gradle.kts
   git commit -m "chore: bump version to 1.1"
   git push
   ```
   The next build on GitHub Actions will automatically start numbering with your new base version prefix (e.g., `v1.1.13`).

### Upgrade and Downgrade Constraints
Android OS enforces specific constraints for security and data integrity:

* **Upgrades**: Users can install an updated APK directly over an existing installation only if the incoming APK's `versionCode` is **strictly higher** than the currently installed version.
* **Downgrades**: Android does **not** allow installing an APK with a lower `versionCode` over a higher one (results in `INSTALL_FAILED_VERSION_DOWNGRADE`).

> [!WARNING]
> **Workflow Reset Warning:** If you delete or recreate the GitHub workflow, the GitHub run number will reset back to `1`. If this occurs, existing devices with previous builds (e.g., run number `42`) will fail to install newer builds until they first **uninstall** the old app (which will delete the app's local state), or until you manually increment the base version/versioning logic.
