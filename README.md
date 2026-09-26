# Texting-Helper
An app to help you be better about not forgetting to text people who are varying degrees of important to you

## What it does
Personal Android app (Kotlin, Jetpack Compose). Set how often you want to text each contact; a daily
check reminds you who's due, with a Claude-drafted suggestion based on your recent 1:1 messages.
Sideloaded, not on the Play Store.

## Build and install (Windows, PowerShell)
Needs Android Studio (for the SDK and bundled JDK) and a phone with USB debugging on.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew assembleDebug
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

Unit tests: `.\gradlew testDebugUnitTest`

The Claude API key is entered in the app's Settings and stored encrypted on the phone; it is never in this repo.
