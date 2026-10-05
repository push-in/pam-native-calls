# PAM Native Calls

System call surfaces for [PAM Native](https://github.com/push-in/pam-native)
applications — the part of a calling app that must work while PHP is suspended.

- **Incoming calls** — `Notification.CallStyle` (Android 12+) with Accept/Decline,
  a ringtone channel with insistent ringing, ring timeout, and a native
  full-screen lock-screen UI launched by the full-screen intent.
- **Push-driven ringing** — `Calls::fromPush()` persists a mapping; the plugin's
  push receiver turns matching data pushes into incoming calls (and clears them
  on "ended" pushes) without waking PHP.
- **Ongoing calls** — CallStyle notification with chronometer and hang-up, owned
  by a `camera|microphone` foreground service so capture survives backgrounding.
- **Call actions** — Accept, Decline, Open, HangUp and Timeout are queued
  natively (surviving process death) and delivered to `Calls::onAction()` when
  PHP resumes.
- **Lock screen** — after Accept, and while a call is ongoing, the app's
  activities show over the lock screen and turn the screen on.

Android API 26+ and iOS 15+.

On iOS the same API drives **CallKit** and **PushKit**: incoming calls ring
with the system call UI (lock screen included), Accept/Decline/HangUp come
from `CXProviderDelegate` and are queued exactly like Android actions, ongoing
calls (including outgoing calls started in the app) are registered with
CallKit, and `Calls::fromPush()` mappings are evaluated natively for **VoIP
pushes** so the phone rings while PHP is suspended or the app was killed.
Send iOS call pushes through APNs VoIP (`apns-push-type: voip`) to the token
from `Calls::voipToken()`; iOS requires every VoIP push to report a call, so
unmatched or "ended" pushes report a call that ends immediately. The plugin
declares the `voip`, `audio` and `remote-notification` background modes and
the `aps-environment` entitlement. CallKit owns the ringtone and the
full-screen UI (`readiness()` reports `true` unless the device region is
mainland China, where CallKit is unavailable). When combining with
`pam-native-webrtc`, start call audio after CallKit posts
`Notification.Name.pamCallAudioActivated`. The iOS implementation has not been
validated on a device yet; see `ios/Tests/CallsTests.swift`.

## Install

```bash
pam composer require pushinbr/pam-native-calls
```

Requires `pushinbr/pam-native` `>=1.0.35 <2.0.0`. Declared permissions:
`POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_MICROPHONE`, `CAMERA`,
`RECORD_AUDIO`, `VIBRATE`, `WAKE_LOCK`. Request notification permission at
runtime with the core `Permissions` API.

## Incoming call

```php
use Pam\Native\Calls\{Calls, IncomingCall};

Calls::incoming(
    IncomingCall::make($callId)
        ->caller($name, $avatarUrl)          // https URL or sandbox-relative path
        ->video()
        ->timeout(45)                        // seconds, then a Timeout action
        ->deepLink("myapp://call/{$callId}") // opened on Accept/Open
        ->labels('Atender', 'Recusar')       // defaults are localized (en, pt)
        ->data(['room' => $roomId]),         // echoed back in every CallAction
)->show();
```

## Actions

```php
use Pam\Native\Calls\{CallAction, CallActionKind, Calls};

Calls::onAction(function (CallAction $action): void {
    match ($action->kind) {
        CallActionKind::Accept  => $this->join($action->callId, $action->data),
        CallActionKind::Decline => $this->api->decline($action->callId),
        CallActionKind::Open    => $this->openCallScreen($action->callId),
        CallActionKind::HangUp  => $this->hangUp($action->callId),
        CallActionKind::Timeout => $this->api->missed($action->callId),
    };
});
```

| `CallActionKind` | Value | Source |
| --- | --- | --- |
| `Accept` | 1 | Notification button or lock-screen UI (opens the app) |
| `Decline` | 2 | Notification button or lock-screen UI |
| `Open` | 3 | Tapping the notification (opens the app) |
| `HangUp` | 4 | Ongoing notification button |
| `Timeout` | 5 | Ring timeout elapsed |

## Ongoing call

```php
Calls::ongoing($callId)->since($connectedAt)->caller($name, $avatar)->video()->show();
Calls::end($callId);   // removes every surface, emits no action
Calls::endAll();
```

## Ringing from push while PHP is suspended

Register once at boot. Values are compared as strings.

```php
use Pam\Native\Calls\{Calls, PushCallMapping};

Calls::fromPush(
    PushCallMapping::type('call.incoming')      // data['type'] === 'call.incoming'
        ->id('call_id')
        ->caller('caller_name', 'caller_avatar')
        ->video('call_type', 'video')
        ->endedWhen('call_event', 'ended')       // clears the ringing UI
        ->deepLink('deep_link')
        ->timeout(45),
);
```

## iOS VoIP token

```php
Calls::voipToken(fn (?string $token) => $token && $this->api->registerVoip($token));
```

`null` on Android (ring from FCM data pushes) and until iOS issues a token.

## Readiness

```php
use Pam\Native\Calls\CallReadiness;

Calls::readiness(function (CallReadiness $r): void {
    if (!$r->fullScreenIntentAllowed) {
        Calls::openFullScreenSettings();   // Android 14+ special access
    }
});
```

## Tests

```bash
pam tests/run.php
cd android && ANDROID_SERIAL=emulator-5558 ../../../pam-native/android/gradlew -p . connectedDebugAndroidTest
```

The instrumented suite verifies CallStyle incoming/ongoing notifications, the
full-screen intent and ringtone channel, Decline/Timeout/Accept/HangUp flows,
the lock-screen UI, the foreground service, push mapping (show and end) and the
persistent action queue.

## License

Apache-2.0
