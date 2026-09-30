# SETUP.md

How to set up an Ubuntu workstation to build, test and sideload SMS
Forwarder. By the end you'll have a debug APK installed on the forwarder
phone (Pixel 8a).

This file covers the development environment only. Configuring the phone
itself (bot token, chat, key and the unattended-operation checklist) is in
`Specification.md` §4 and §9.

## Versions

`Specification.md` §1 is the source of truth for these versions. Confirm
with the project owner before changing any of them.

| Component             | Version | Installed how                              |
| --------------------- | ------- | ------------------------------------------ |
| JDK                   | 17      | apt (below)                                |
| Gradle                | 8.9     | Gradle wrapper, bootstrapped once (below)  |
| Android Gradle Plugin | 8.5.2   | Downloaded by Gradle                       |
| Kotlin                | 2.0.20  | Downloaded by Gradle (no separate install) |
| Android SDK platform  | 34      | `sdkmanager` or Android Studio (below)     |
| Android build-tools   | 34.0.0  | `sdkmanager` or Android Studio (below)     |
| minSdk                | 31      | Android 12+ on the Pixel 8a                |

You don't install Kotlin yourself. The Kotlin Gradle plugin brings the
compiler, and Gradle downloads every library dependency on the first build.

## 1. Install JDK 17

```sh
sudo apt update
sudo apt install openjdk-17-jdk
```

Gradle 8.9 runs only on Java 8–22, and the app compiles for Java 17. If your
default `java` is newer (Ubuntu 26.04 ships OpenJDK 25, and so does the JBR
bundled with current Android Studio), Gradle fails before building anything.
Point `JAVA_HOME` at JDK 17. With `JAVA_HOME` set, you don't need to touch
`update-alternatives`. Add this to `~/.bashrc`:

```sh
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
```

Open a new shell and check:

```sh
java -version    # openjdk version "17.x"
```

## 2. Install the Android SDK

You need `platform-tools` (for `adb`), `platforms;android-34` and
`build-tools;34.0.0`, and you must accept the SDK licenses. Use either
option.

### Option A: Android Studio

Install Android Studio, open **Settings → Languages & Frameworks → Android
SDK**, and tick Android 14 (API 34) under *SDK Platforms* and 34.0.0 under
*SDK Tools → Android SDK Build-Tools* (enable *Show Package Details*). The
SDK is installed to `~/Android/Sdk` by default.

### Option B: command line only

Download the "Command line tools only" zip for Linux from
<https://developer.android.com/studio#command-line-tools-only>, then:

```sh
mkdir -p ~/Android/Sdk/cmdline-tools
unzip ~/Downloads/commandlinetools-linux-*_latest.zip -d ~/Android/Sdk/cmdline-tools
mv ~/Android/Sdk/cmdline-tools/cmdline-tools ~/Android/Sdk/cmdline-tools/latest
```

### Both options

Add this to `~/.bashrc`:

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
```

Then install the packages and accept the licenses. Option A needs the
**Android SDK Command-line Tools** package from the SDK Manager first,
because that package provides `sdkmanager`.

```sh
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
sdkmanager --licenses
```

## 3. Point the project at the SDK

Gradle finds the SDK through `sdk.dir` in `local.properties` at the repo
root. If that setting is missing, it uses `ANDROID_HOME`. The path is
specific to each machine, so set your own:

```properties
sdk.dir=/home/<you>/Android/Sdk
```

## 4. Bootstrap the Gradle wrapper (once)

`gradlew` and `gradlew.bat` in the repo are placeholder stubs, and
`gradle/wrapper/gradle-wrapper.jar` is missing (`Specification.md` §11).
Ubuntu's apt `gradle` package is too old to generate them, so use the
official 8.9 distribution:

```sh
cd /tmp
wget https://services.gradle.org/distributions/gradle-8.9-bin.zip
unzip -q gradle-8.9-bin.zip

cd <repo root>
/tmp/gradle-8.9/bin/gradle wrapper --gradle-version 8.9
chmod +x gradlew
./gradlew --version    # Gradle 8.9, JVM 17
```

This command overwrites `gradlew` and `gradlew.bat` and creates the wrapper
jar. Commit all three so nobody has to repeat this step. After that,
`./gradlew` downloads Gradle 8.9 automatically, and the Gradle in `/tmp` can
be deleted.

## 5. Build, test, lint

```sh
./gradlew :app:assembleDebug       # build the debug APK
./gradlew :app:testDebugUnitTest   # JVM unit tests
./gradlew :app:lintDebug           # Android Lint
```

The first build downloads AGP, Kotlin and every dependency, so it takes a
while.

- The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

- Unit tests run on the host JVM. There is no `androidTest` (on-device)
  suite.

- To run one class or one test:
  
  ```sh
  ./gradlew :app:testDebugUnitTest --tests 'dev.smsforwarder.telegram.TelegramSenderTest'
  ./gradlew :app:testDebugUnitTest --tests 'dev.smsforwarder.telegram.TelegramClientTest.errors_areClassified'
  ```

- No ktlint or detekt is configured. Lint is the only static check.

## 6. Install on the phone

The app isn't distributed through a store, so you sideload it over USB.

1. On the Pixel 8a, open **Settings → About phone** and tap **Build number**
   seven times to enable Developer options.

2. Open **Settings → System → Developer options** and turn on **USB
   debugging**.

3. Connect the phone by USB and check that it's visible:
   
   ```sh
   adb devices    # should list the phone as "device"
   ```
   
   Accept the "Allow USB debugging?" prompt on the phone the first time.

4. Install:
   
   ```sh
   ./gradlew :app:installDebug
   ```

On Android 15+, the SMS permission for a sideloaded app is blocked until you
open **App info → ⋮ → Allow restricted settings** (`Specification.md` §9).

## 7. Optional: Android Studio

Open the repo root as a project. Then open **Settings → Build, Execution,
Deployment → Build Tools → Gradle** and set **Gradle JDK** to JDK 17
(`/usr/lib/jvm/java-17-openjdk-amd64`). Studio's bundled JBR is too new for
Gradle 8.9. Complete step 4 first so Studio can use the wrapper.

## 8. Later: the Mini App

The Telegram Mini App (`Specification.md` §13) isn't built yet. Once
`miniapp/` exists, its unit tests will need Node.js ≥ 20 (`node --test`).
There's nothing to install for it today.

## Troubleshooting

| Symptom                                                                                                               | Fix                                                                                       |
| --------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| `Unsupported class file major version 69` or other Java-version errors from Gradle                                    | Gradle is running on a JDK newer than 22. Set `JAVA_HOME` to JDK 17 (step 1).             |
| `./gradlew: Permission denied`, `./gradlew` does nothing, or `Could not find or load main class ...GradleWrapperMain` | The wrapper hasn't been bootstrapped. Do step 4.                                          |
| `SDK location not found`                                                                                              | Set `sdk.dir` in `local.properties` or `ANDROID_HOME` (step 3).                           |
| `Failed to install the following Android SDK packages as some licences have not been accepted`                        | Run `sdkmanager --licenses`.                                                              |
| `adb devices` shows `unauthorized`                                                                                    | Unlock the phone and accept the USB debugging prompt. Re-plug if the prompt doesn't show. |
| `adb devices` shows nothing                                                                                           | Check the USB cable (it must carry data) and that USB debugging is on.                    |
