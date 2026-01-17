# Native UI Migration Plan

## Goal
Replace the WebView-based video display with native Android components for better performance and reliability.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                           MainActivity                               │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │                        VideoView (ImageView)                    │ │
│  │                     [Native MJPEG Display]                      │ │
│  └────────────────────────────────────────────────────────────────┘ │
│  ┌──────────────────┬─────────────────────────────────────────────┐ │
│  │ Status Overlay   │  Controls Panel (Collapsible)               │ │
│  │ - Motion score   │  - Zoom slider (1x-4x)                      │ │
│  │ - Alarm status   │  - Contrast slider (1.0-3.0)                │ │
│  │ - Connection     │  - Brightness slider (-50 to +50)           │ │
│  └──────────────────┴─────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
         │                              │
         ▼                              ▼
┌─────────────────────┐      ┌─────────────────────┐
│   MjpegStreamJob    │      │     ApiClient       │
│ (Coroutine on IO)   │      │   (Singleton)       │
│                     │      │                     │
│ - Reads /video_feed │      │ - getStatus()       │
│ - Parses JPEG frames│      │ - setEnhancements() │
│ - Posts to UI       │      │ - getSettings()     │
└─────────────────────┘      └─────────────────────┘
```

## Files to Create/Modify

### NEW FILES

#### 1. `network/MjpegInputStream.kt`
- Wraps an InputStream from OkHttp response body
- `suspend fun readFrame(): Bitmap?`
  - Reads until boundary `--frame\r\n`
  - Skips headers until `\r\n\r\n`
  - Reads JPEG bytes until next boundary (look for 0xFFD9 end marker)
  - Decodes to Bitmap using BitmapFactory

#### 2. `network/ApiClient.kt`
- Singleton with OkHttpClient
- Methods:
  - `suspend fun getVideoStream(baseUrl: String): Response` - Returns raw OkHttp Response for streaming
  - `suspend fun getStatus(baseUrl: String): StatusResponse`
  - `suspend fun getSettings(baseUrl: String): SettingsResponse`
  - `suspend fun setEnhancements(baseUrl: String, zoom: Float?, contrast: Float?, brightness: Int?)`

#### 3. `data/StatusResponse.kt` (Move from MonitoringService)
```kotlin
data class StatusResponse(
    val motion_detected: Boolean,
    val motion_score: Double,
    val alarm_active: Boolean,
    val seconds_since_motion: Int
)
```

#### 4. `data/SettingsResponse.kt`
```kotlin
data class SettingsResponse(
    val zoom: Float,
    val contrast: Float,
    val brightness: Int,
    val has_roi: Boolean,
    val roi: List<Float>?
)
```

### MODIFIED FILES

#### 1. `activity_main.xml`
Replace WebView with:
```xml
<!-- Video Container -->
<FrameLayout>
    <ImageView android:id="@+id/videoView" 
               android:scaleType="fitCenter"/>
    
    <!-- Status Overlay (top-left) -->
    <LinearLayout android:id="@+id/statusOverlay">
        <TextView android:id="@+id/motionScoreText"/>
        <TextView android:id="@+id/alarmStatusText"/>
        <TextView android:id="@+id/connectionStatusText"/>
    </LinearLayout>
</FrameLayout>

<!-- Controls Panel (collapsible bottom sheet) -->
<LinearLayout android:id="@+id/controlsPanel">
    <SeekBar android:id="@+id/zoomSeekBar"/>
    <SeekBar android:id="@+id/contrastSeekBar"/>
    <SeekBar android:id="@+id/brightnessSeekBar"/>
</LinearLayout>
```

#### 2. `MainActivity.kt`
Major refactor:
- Remove all WebView code
- Add coroutine scopes for:
  - Video streaming (IO dispatcher)
  - Status polling (IO dispatcher, every 1.5s)
- Implement reconnection with exponential backoff
- Add UI bindings for new controls

#### 3. `MonitoringService.kt`
- Extract StatusResponse to shared data class
- Keep existing functionality (background alarm notifications)

### KEEP AS-IS
- `SettingsActivity.kt` - Server URL config still needed
- `BootReceiver.kt` - Auto-start still useful
- All drawable resources

## Implementation Order

1. **[STEP 1]** Create data models (`StatusResponse.kt`, `SettingsResponse.kt`)
2. **[STEP 2]** Create `ApiClient.kt` with suspend functions  
3. **[STEP 3]** Create `MjpegInputStream.kt` with frame parsing
4. **[STEP 4]** Modify `activity_main.xml` layout
5. **[STEP 5]** Refactor `MainActivity.kt` 
6. **[STEP 6]** Update `MonitoringService.kt` to use shared ApiClient
7. **[STEP 7]** Add dependencies (coroutines if missing)
8. **[STEP 8]** Test and refine

## MJPEG Parsing Strategy

The Flask backend sends MJPEG in this format:
```
--frame\r\n
Content-Type: image/jpeg\r\n
\r\n
<JPEG BYTES: starts with 0xFFD8, ends with 0xFFD9>
\r\n
--frame\r\n
...
```

Parsing algorithm:
1. Read line by line until we see `--frame`
2. Skip headers until empty line (`\r\n\r\n`)
3. Read bytes until we encounter `--frame` again
4. The bytes before `--frame` (minus trailing \r\n) are the JPEG
5. Decode JPEG bytes using `BitmapFactory.decodeByteArray()`

## Reconnection Strategy

```kotlin
private var reconnectDelay = 1000L // Start at 1 second
private const val MAX_DELAY = 30000L // Max 30 seconds

fun onConnectionLost() {
    updateConnectionStatus("Reconnecting in ${reconnectDelay/1000}s...")
    delay(reconnectDelay)
    reconnectDelay = minOf(reconnectDelay * 2, MAX_DELAY)
    attemptConnect()
}

fun onConnectionSuccess() {
    reconnectDelay = 1000L // Reset delay
    updateConnectionStatus("Connected")
}
```

## Error Handling

1. **Network timeout**: Show "Connection lost" → auto-reconnect
2. **Server not found**: Show "Server unreachable" → show reconnect button
3. **Invalid JPEG frame**: Skip frame, continue streaming
4. **Memory pressure**: Use `inSampleSize` in BitmapFactory if needed

## Testing Checklist

- [ ] Video displays and updates smoothly
- [ ] Motion score updates in real-time
- [ ] Alarm status shows correctly (text turns red when alarm active)
- [ ] Zoom slider works (calls /set_enhancements)
- [ ] Contrast slider works
- [ ] Brightness slider works  
- [ ] Stream recovers after network blip (within 5 seconds)
- [ ] Stream keeps running with screen off (if battery optimization disabled)
- [ ] Background monitoring still works (MonitoringService)
- [ ] App starts correctly after reboot (BootReceiver)
