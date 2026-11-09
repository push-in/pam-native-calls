# PAM Native Calls: install and platform notes

> Moved out of the README; nothing was removed.

## Install

```bash
pam composer require pushinbr/pam-native-calls
pam doctor --fix
```

The package is a PAM Native plugin (`extra.pam-native.plugin`). The build
discovers it from Composer and generates the native integration; there is no
`pam-native.json` entry to add. It requires `pushinbr/pam-native`
`>=1.0.35 <2.0.0` and PHP 8.5.

### Android

The plugin manifest is merged into the app. It declares:

| Permission | Why |
| --- | --- |
| `POST_NOTIFICATIONS` | Show the ringing and ongoing notifications (runtime permission on Android 13+). |
| `USE_FULL_SCREEN_INTENT` | Launch the lock-screen call UI. Android 14+ grants it only to calling/alarm apps; users can toggle it (see [readiness](guide.md#readiness)). |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_MICROPHONE` | Keep capture alive during an ongoing call. |
| `CAMERA`, `RECORD_AUDIO` | Required by the `camera\|microphone` foreground service type. Request them at runtime before `Calls::ongoing()->show()`. |
| `VIBRATE`, `WAKE_LOCK`, `INTERNET` | Ring pattern, screen wake and avatar download. |

It also registers `IncomingCallActivity` (full-screen call UI),
`CallActionActivity`/`CallActionReceiver` (button actions), `CallPushReceiver`
(listens to the core `dev.pam.nativeapp.action.PUSH_RECEIVED` broadcast) and
`CallForegroundService` (`foregroundServiceType="camera|microphone"`).
Notification channels: `pam_calls_incoming` (high importance, default ringtone
with `USAGE_NOTIFICATION_RINGTONE`, insistent) and `pam_calls_ongoing` (silent).
Strings are localized in English and Portuguese.

Push-driven ringing on Android uses **FCM data messages** delivered by the PAM
Native core push stack, so the app needs Firebase configured
(`google-services.json`) and `PushNotifications` registered. Send the call as a
**data-only, high-priority** message; a `notification` payload is shown by the
system and never reaches the receiver while the app is in the background.

Play policy: `USE_FULL_SCREEN_INTENT` must be declared in the Play Console as a
calling app permission.

### iOS

The plugin merges into the generated Xcode project:

- `UIBackgroundModes`: `voip`, `audio`, `remote-notification` (`ios/Info.plist`);
- `aps-environment` = `development` (`ios/Calls.entitlements`); your provisioning
  profile must include Push Notifications, and distribution exports use the
  profile's `production` value;
- frameworks `CallKit`, `PushKit`, `AVFoundation`.

Camera and microphone usage strings are not declared here; they come from the
package that captures media (`pam-native-webrtc` or `pam-native-media` declare
`NSCameraUsageDescription` and `NSMicrophoneUsageDescription`).

`CallsModule` registers PushKit when the app launches. Send iOS call pushes
through APNs **VoIP** (`apns-push-type: voip`, topic `<bundle id>.voip`) to the
token from [`Calls::voipToken()`](guide.md#ios-voip-token). The push's top-level keys
(everything except `aps`) are the data your [`PushCallMapping`](guide.md#ringing-from-push-while-php-is-suspended)
reads; `aps.alert.title` is the fallback caller name.
