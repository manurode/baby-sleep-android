# Baby Sleep Monitor - Android App

A native Android companion app for the Baby Sleep Monitor that runs in the background and alerts you with system notifications when no movement is detected.

## Features

- 📱 **WebView Interface**: Full access to the baby monitor web UI directly in the app
- 🔔 **Background Monitoring**: Foreground service that monitors even when the app is closed or screen is off
- 🚨 **High-Priority Notifications**: Alarm notifications with sound and vibration when no movement is detected for 10 seconds
- 🌙 **Screen-Off Operation**: Keeps monitoring even when your phone screen is off using Wake Locks
- 🔄 **Auto-Recovery**: Automatically cancels alerts when movement resumes

---

## Prerequisites

Before you begin, ensure you have:

1. **Android Studio** (latest stable version recommended)
   - Download from: https://developer.android.com/studio
   - During installation, make sure to install the Android SDK

2. **Java Development Kit (JDK) 17 or higher**
   - Android Studio usually bundles this, but verify in: File → Project Structure → SDK Location

3. **An Android device or emulator** (API Level 26 / Android 8.0 or higher)

4. **The Baby Sleep Monitor server running** on a computer on your local network

---

## Step-by-Step: Opening in Android Studio

### 1. Install Android Studio

1. Go to https://developer.android.com/studio
2. Download and run the installer
3. Follow the setup wizard:
   - Select "Standard" installation type
   - Accept all licenses
   - Wait for component downloads to complete

### 2. Open the Project

1. Launch Android Studio
2. Click **"Open"** (or File → Open)
3. Navigate to `C:\Repos\baby-sleep-android`
4. Click **OK**
5. Wait for the project to sync (this may take several minutes the first time)

### 3. First-Time Sync

When you open the project for the first time:

1. Android Studio will prompt to download Gradle if needed - click **OK**
2. If prompted about SDK versions, click **Install missing SDK components**
3. Wait for "Gradle sync" to complete (see bottom progress bar)
4. If you see any error messages, try: **File → Sync Project with Gradle Files**

---

## Building the App

### Option 1: Build Debug APK (Easiest for Testing)

1. In Android Studio, go to: **Build → Build Bundle(s) / APK(s) → Build APK(s)**
2. Wait for the build to complete
3. Click **"locate"** in the notification popup that appears
4. The APK will be at: `app/build/outputs/apk/debug/app-debug.apk`

### Option 2: Build Using Command Line

Open a terminal in the project directory and run:

```powershell
# Windows - Debug APK
.\gradlew.bat assembleDebug

# The APK will be at: app\build\outputs\apk\debug\app-debug.apk
```

### Option 3: Build Release APK (For Distribution)

1. First, create a signing key (one-time):
   ```powershell
   keytool -genkey -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias baby-monitor
   ```

2. Add signing config to `app/build.gradle.kts`:
   ```kotlin
   android {
       signingConfigs {
           create("release") {
               storeFile = file("my-release-key.jks")
               storePassword = "your-password"
               keyAlias = "baby-monitor"
               keyPassword = "your-key-password"
           }
       }
       buildTypes {
           release {
               signingConfig = signingConfigs.getByName("release")
               // ... other settings
           }
       }
   }
   ```

3. Build the release APK:
   ```powershell
   .\gradlew.bat assembleRelease
   ```

4. APK location: `app\build\outputs\apk\release\app-release.apk`

---

## Installing the App on Your Phone

### Method 1: Direct USB Install (Recommended)

1. **Enable Developer Mode on your phone:**
   - Go to Settings → About Phone
   - Tap "Build Number" 7 times
   - You'll see "You are now a developer!"

2. **Enable USB Debugging:**
   - Go to Settings → Developer Options
   - Turn on "USB Debugging"

3. **Connect your phone via USB:**
   - When prompted on your phone, tap "Allow" for USB debugging

4. **Run from Android Studio:**
   - Click the green "Run" button (▶️) in the toolbar
   - Select your device from the list
   - The app will install and launch

### Method 2: Install APK Manually

1. Transfer the APK file to your phone (via USB, email, or cloud storage)

2. On your phone, enable "Install from unknown sources":
   - Settings → Security → Install unknown apps
   - Allow for the app you're using to install (e.g., Files, Chrome)

3. Open the APK file on your phone

4. Tap "Install"

5. Once installed, open "Baby Sleep Monitor"

---

## Using the App

### Initial Setup

1. **Find your server IP address:**
   - On Windows: Open Command Prompt and run `ipconfig`
   - Look for "IPv4 Address" under your Wi-Fi adapter (e.g., `192.168.1.100`)

2. **Start the Baby Monitor server:**
   ```powershell
   cd C:\Repos\baby-sleep
   python app.py
   ```
   - Server will start on port 5000

3. **Open the Android app and enter the server URL:**
   - Format: `http://YOUR_IP_ADDRESS:5000`
   - Example: `http://192.168.1.100:5000`

### Viewing the Monitor

1. Enter your server URL
2. Tap **"Connect to Monitor"**
3. The WebView will load the full baby monitor interface
4. You can use all features: ROI selection, zoom, contrast, brightness

### Enabling Background Monitoring

**This is the key feature for screen-off alerts!**

1. Tap **"Start Background Monitoring"**
2. Grant notification permission when prompted
3. You'll see a persistent notification: "Baby Monitor Active"
4. The app now monitors in the background!

### Testing the Alarm

1. With background monitoring enabled
2. Cover the camera (or wait for no movement)
3. After 10 seconds, you should receive:
   - A high-priority notification
   - Sound alarm
   - Vibration pattern

---

## Troubleshooting

### "Cannot connect to server"

1. Verify the server is running (`python app.py`)
2. Check you're on the same Wi-Fi network as the server
3. Verify the IP address is correct
4. Ensure port 5000 is not blocked by firewall:
   ```powershell
   # Windows - Allow Python through firewall
   netsh advfirewall firewall add rule name="Baby Monitor" dir=in action=allow protocol=TCP localport=5000
   ```

### "No notification sound"

1. Check phone is not on silent/vibrate mode
2. Go to Settings → Apps → Baby Sleep Monitor → Notifications
3. Ensure "Baby Monitor Alarm" channel is set to high priority with sound

### "App stops monitoring when phone sleeps"

1. Go to Settings → Apps → Baby Sleep Monitor → Battery
2. Select "Unrestricted" or "Don't optimize"
3. This prevents Android from killing the background service

### Build errors in Android Studio

1. **Sync issues**: File → Sync Project with Gradle Files
2. **SDK missing**: Tools → SDK Manager → Install required SDK
3. **Invalidate caches**: File → Invalidate Caches → Invalidate and Restart

---

## Project Structure

```
baby-sleep-android/
├── app/
│   ├── src/main/
│   │   ├── java/com/babysleepmonitor/
│   │   │   ├── MainActivity.kt       # Main UI with WebView
│   │   │   ├── MonitoringService.kt  # Background service
│   │   │   ├── SettingsActivity.kt   # Settings screen
│   │   │   └── BootReceiver.kt       # Auto-start on boot
│   │   ├── res/
│   │   │   ├── layout/               # UI layouts
│   │   │   ├── values/               # Colors, strings, themes
│   │   │   └── drawable/             # Button styles, icons
│   │   └── AndroidManifest.xml       # App permissions & config
│   └── build.gradle.kts              # App dependencies
├── gradle/
│   └── libs.versions.toml            # Version catalog
├── build.gradle.kts                  # Project config
├── settings.gradle.kts               # Project settings
└── README.md                         # This file
```

---

## Technical Details

### Permissions Used

| Permission | Purpose |
|------------|---------|
| `INTERNET` | Connect to the baby monitor server |
| `FOREGROUND_SERVICE` | Run background monitoring service |
| `POST_NOTIFICATIONS` | Show alarm notifications |
| `VIBRATE` | Vibrate on alarm |
| `WAKE_LOCK` | Keep service running when screen off |

### API Endpoint Used

The app polls: `GET http://<server>/status`

Response format:
```json
{
  "motion_detected": true,
  "motion_score": 1234.5,
  "alarm_active": false,
  "seconds_since_motion": 2
}
```

### Notification Channels

1. **baby_monitor_service** (Low priority): Persistent "monitoring active" notification
2. **baby_monitor_alarm** (High priority): Alarm notification with sound/vibration

---

## License

Personal use only. Part of the Baby Sleep Monitor project.