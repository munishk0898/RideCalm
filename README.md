# RideCalm: Vehicle Motion Cues for Android

## Download

Latest APK: [`apk/RideCalm.apk`](apk/RideCalm.apk) (version 1.1.0, about 1.1 MB). Every build is also on the [Releases](../../releases) page.

A native Android take on Apple's Vehicle Motion Cues. Animated dots along the screen edges move with the vehicle (accelerating, braking, turning), which helps reduce motion sickness while a passenger looks at their phone.

- Kotlin, Jetpack Compose (Material 3), Jetpack DataStore
- minSdk 26 (Android 8.0), targetSdk / compileSdk 35 (Android 15)
- AGP 8.7.3, Kotlin 2.0.21, Gradle 8.11.1 (wrapper included)

## Build

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # sensor math unit tests
```

Or open the folder in Android Studio (Ladybug or newer) and run the `app` configuration. You need JDK 17+ and the Android SDK Platform 35.

## How it works

| Layer | Files |
|---|---|
| Sensor math (pure Kotlin, unit tested) | `motion/` : `Vec3`, `ScreenAxes`, `VehicleFrame`, `Filters`, `MotionProcessor`, `Spring`, `CueMapping`, `SimulationScript` |
| Sensors | `sensors/SensorController.kt` : linear acceleration, gravity and gyroscope on a background thread; tracks display rotation, including 0° to 180° flips |
| Rendering | `overlay/DotRenderer.kt` (shared by the overlay and the in-app preview), `CueOverlayView.kt`, `OverlayController.kt` |
| Service | `service/MotionCueService.kt` (foreground service, `specialUse`), `ModeController.kt`, `CueNotifications.kt`, `BootReceiver.kt` |
| Automatic mode | `motion/VehicleClassifier.kt` (on-device, unit tested) and `detection/VehicleState.kt` |
| Quick Settings | `tile/MotionCueTileService.kt` : cycles Off, On, Automatic |
| UI | `ui/MainScreen.kt`, `ui/MotionPreview.kt`, `ui/MainViewModel.kt` |

### Motion pipeline

1. **Vehicle frame.** Gravity defines the horizontal plane. The forward direction is the sum of the horizontal projections of the screen's up axis and the into-screen direction. This works whether the phone is held upright, tilted, or lying flat, in any of the four display rotations.
2. **Projection.** Linear acceleration is projected onto forward and right. The gyroscope's rotation about the gravity axis (yaw) adds a turn cue that responds even at low speed.
3. **Smoothing.** First-order low-pass filters are defined by time constants (sample-rate independent), followed by a soft dead zone that removes road vibration and hand tremor.
4. **Mapping.** `tanh` soft saturation, scaled by the Sensitivity slider. Forward acceleration moves the dots down, braking moves them up, and a left turn moves them right.
5. **Physics.** Spring-damper per axis. *Regular* is critically damped, so the dots snap back to rest without overshoot. *Dynamic* uses three parallax layers with softer, slightly under-damped springs, wrap-around flow and edge fading.

### Overlay details

- `TYPE_APPLICATION_OVERLAY` with `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_IN_SCREEN`, created from a window context on Android 11+.
- Window alpha is capped at the system's maximum obscuring opacity (0.8). Above that, Android 12+ blocks touches passing through to the app underneath.
- Animation runs on `Choreographer`, and the view only invalidates when the dots actually move.
- Sensors pause while the screen is off. In Automatic mode the sensors stay off until a vehicle is detected.

### Background-start restrictions

Android 12+ limits starting foreground services from the background. To work within that:
- In Automatic mode the service stays in the foreground (cheaply, with sensors off) so vehicle transitions can show the overlay without a new service start.
- If the Quick Settings tile or the boot receiver is refused a start, the tile opens the app. The activity then starts the service in `onResume`, which is always allowed.

## Privacy and footprint

- **Fully offline.** The manifest removes `INTERNET` and `ACCESS_NETWORK_STATE`, so the app cannot open a network connection. There are no Google Play services, analytics or crash reporters.
- **Nothing leaves the phone.** Cloud backup and device-to-device transfer are disabled (`allowBackup="false"` and `data_extraction_rules.xml`). Sensor data is processed in memory and never stored. The only file written is the small settings store.
- **Automatic mode runs on the device.** `VehicleClassifier` looks for sustained horizontal acceleration (speeding up, braking, turning) with no steps. It runs only while the screen is on, at 10 Hz with sensor batching. Full-rate 50 Hz sensors and the gyroscope run only while dots are visible, and every sensor is off while the screen is off.
- **Small.** The release build uses R8 code and resource shrinking, English resources only, and no tooling dependencies.

## Signing

CI signs builds with `keystore/debug.keystore`, a non-secret sideload key, so each build installs over the previous one. For Play Store distribution, set `RIDECALM_KEYSTORE`, `RIDECALM_KEYSTORE_PASSWORD`, `RIDECALM_KEY_ALIAS` and `RIDECALM_KEY_PASSWORD` to use your own private key.

## Google Play notes

- `FOREGROUND_SERVICE_SPECIAL_USE` requires a justification in the Play Console. The manifest `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` already describes the accessibility use.
- `SYSTEM_ALERT_WINDOW` and the optional `ACTIVITY_RECOGNITION` need a clear in-app explanation. The permission cards on the main screen provide it.
- No location, network or account permissions are requested.
