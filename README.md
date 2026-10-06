<!-- pam:product-page:start -->
<div align="center">

# PAM Native Calls

**Ring like a phone call, even while PHP is suspended.**

System call surfaces for PAM Native: CallStyle and CallKit incoming calls, a lock-screen call UI, an ongoing-call foreground service, a durable action queue and push-driven ringing.

[![Latest version](https://img.shields.io/packagist/v/pushinbr/pam-native-calls?style=flat-square&label=stable)](https://packagist.org/packages/pushinbr/pam-native-calls)
![PHP](https://img.shields.io/badge/PHP-8.5-777BB4?style=flat-square&logo=php&logoColor=white)
![Android](https://img.shields.io/badge/Android-API%2026%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![iOS](https://img.shields.io/badge/iOS-15%2B-000000?style=flat-square&logo=apple&logoColor=white)

**[Documentation](https://push-in.github.io/pam-docs/packages/native-calls/) · [Quick start](#quick-start) · [API reference](#api-reference) · [PAM ecosystem](https://push-in.github.io/pam-docs/ecosystem/) · [Issues](https://github.com/push-in/pam-native-calls/issues)**

</div>

---

## Why PAM Native Calls

A calling app has a part that must work when your PHP code is not running:
the phone has to ring from a push, show a full-screen UI on the lock screen,
keep the camera and microphone alive in the background and remember what the
user tapped. This package owns exactly that part, natively, and hands the
result back to PHP when the runtime resumes. Media (WebRTC) and signalling stay
in your app; see [`pushinbr/pam-native-webrtc`](https://github.com/push-in/pam-native-webrtc).

| | |
| --- | --- |
| **Best for** | Voice and video calling, walkie-talkie and support apps |
| **Native path** | `Notification.CallStyle`, full-screen intent and a foreground service on Android · CallKit and PushKit on iOS |
| **Application model** | Composer package + generated native integration (`calls` module) |
| **Design rule** | Surfaces and actions only; no media, signalling or call screen bundled |

## What you can build

- **Incoming calls** — Android 12+ `Notification.CallStyle` with Accept/Decline,
  an insistent ringtone channel, ring timeout and a native full-screen UI over
  the lock screen; CallKit's system UI on iOS.
- **Push-driven ringing** — `Calls::fromPush()` persists a mapping; the native
  push receiver (FCM data message on Android, PushKit VoIP push on iOS) turns
  matching pushes into incoming calls, and clears them on "ended" pushes,
  without waking PHP.
- **Ongoing calls** — CallStyle notification with a chronometer and hang-up,
  owned by a `camera|microphone` foreground service so capture survives
  backgrounding; registered with CallKit on iOS.
- **Durable call actions** — Accept, Decline, Open, HangUp and Timeout are
  queued natively (surviving process death) and delivered to `Calls::onAction()`
  when PHP resumes.
- **Lock screen** — after Accept, and while a call is ongoing, the app's
  activities show over the lock screen and turn the screen on.

## Quick start

Already have a PAM Native project? Add only this capability:

```bash
pam composer require pushinbr/pam-native-calls
pam doctor --fix
```

```php
use Pam\Native\Calls\{CallAction, CallActionKind, Calls, IncomingCall};

// Once at boot (index.php or your root component's boot()).
Calls::onAction(function (CallAction $action): void {
    if ($action->kind === CallActionKind::Accept) {
        // Open your call screen and join $action->callId.
    }
});

// Ring now (for example from a foreground realtime event).
Calls::incoming(
    IncomingCall::make('call-42')->caller('Ana', 'https://cdn.example.com/ana.jpg')->video(),
)->show();
```

New to PAM? Follow the **[five-minute PAM Native setup](https://push-in.github.io/pam-docs/native/overview/)** once, then return here. Your application stays a normal Composer project with a committed lockfile.
<!-- pam:product-page:end -->

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
| `USE_FULL_SCREEN_INTENT` | Launch the lock-screen call UI. Android 14+ grants it only to calling/alarm apps; users can toggle it (see [readiness](#readiness)). |
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
token from [`Calls::voipToken()`](#ios-voip-token). The push's top-level keys
(everything except `aps`) are the data your [`PushCallMapping`](#ringing-from-push-while-php-is-suspended)
reads; `aps.alert.title` is the fallback caller name.

## Incoming call

```php
use Pam\Native\Calls\{Calls, IncomingCall};

Calls::incoming(
    IncomingCall::make($callId)
        ->caller($name, $avatarUrl)          // https URL or sandbox-relative path
        ->video()
        ->timeout(45)                        // seconds, then a Timeout action
        ->subtitle('Zé Chat video call')     // defaults to "Incoming voice/video call"
        ->deepLink("myapp://call/{$callId}") // opened on Accept/Open
        ->labels('Atender', 'Recusar')       // defaults are localized (en, pt)
        ->data(['room' => $roomId]),         // echoed back in every CallAction
)->show(function (bool $ok, string $error): void {
    // $ok is false when the system refused the call (for example CallKit errors).
});
```

`IncomingCall` is immutable: every setter returns a copy. `show()` requires a
caller name.

## Actions

```php
use Pam\Native\Calls\{CallAction, CallActionKind, Calls};

$listener = Calls::onAction(function (CallAction $action): void {
    match ($action->kind) {
        CallActionKind::Accept  => $this->join($action->callId, $action->data),
        CallActionKind::Decline => $this->api->decline($action->callId),
        CallActionKind::Open    => $this->openCallScreen($action->callId),
        CallActionKind::HangUp  => $this->hangUp($action->callId),
        CallActionKind::Timeout => $this->api->missed($action->callId),
    };
});

Calls::offAction($listener);
```

| `CallActionKind` | Value | Source |
| --- | --- | --- |
| `Accept` | 1 | Notification button, lock-screen UI or CallKit answer (opens the app) |
| `Decline` | 2 | Notification button, lock-screen UI or CallKit decline |
| `Open` | 3 | Tapping the notification (opens the app) |
| `HangUp` | 4 | Ongoing notification button or CallKit end |
| `Timeout` | 5 | Ring timeout elapsed |

Actions are stored natively and read one at a time by a long-poll; each one is
delivered once to every registered listener. Actions taken while the process
was dead are delivered after the next launch, as soon as a listener is
registered, so register at boot.

## Ongoing call

```php
Calls::ongoing($callId)
    ->since($connectedAt)        // DateTimeInterface, Unix seconds or Unix milliseconds
    ->caller($name, $avatar)     // falls back to the incoming call's values
    ->video()
    ->subtitle('Tap to return to the call')
    ->hangUpLabel('Desligar')
    ->show();

Calls::end($callId);   // removes every surface (ringing UI, notification, service); emits no action
Calls::endAll();
```

On Android the ongoing notification is owned by a foreground service of type
`camera|microphone`; start it only after the camera/microphone permissions are
granted, or Android 14+ refuses to start the service. On iOS, an ongoing call
that never rang (an outgoing call) is registered with CallKit through
`CXStartCallAction`.

## Ringing from push while PHP is suspended

Register the mapping once at boot. It is persisted natively and replaces any
earlier mappings. Values are compared as strings, so `2` matches both `"2"`
and `2`.

```php
use Pam\Native\Calls\{Calls, PushCallMapping};

Calls::fromPush(
    PushCallMapping::type('call.incoming')      // data['type'] === 'call.incoming'
        ->id('call_id')
        ->caller('caller_name', 'caller_avatar')
        ->subtitle('body')
        ->video('call_type', 'video')
        ->endedWhen('call_event', 'ended')       // clears the ringing UI
        ->deepLink('deep_link')
        ->timeout(45)
        ->labels('Atender', 'Recusar'),
);
```

Matching data push (FCM data message on Android, VoIP push payload on iOS):

```json
{"type": "call.incoming", "call_id": "c-981", "caller_name": "Ana", "caller_avatar": "https://cdn.example.com/a.jpg", "call_type": "video"}
```

and, to stop ringing everywhere (other device answered, caller hung up):

```json
{"type": "call.incoming", "call_id": "c-981", "call_event": "ended"}
```

On iOS every VoIP push must report a call (iOS 13+ terminates apps that do
not), so unmatched pushes and "ended" pushes for an unknown call report a call
that ends immediately. Only send VoIP pushes for calls.

## iOS VoIP token

```php
Calls::voipToken(function (?string $token): void {
    if ($token !== null) {
        $this->api->registerVoipToken($token); // hex string
    }
});
```

`null` on Android (ring from FCM data pushes) and until iOS has issued a token
(the callback waits for PushKit when the token is not known yet).

## Readiness

```php
use Pam\Native\Calls\CallReadiness;

Calls::readiness(function (CallReadiness $r): void {
    if (!$r->fullScreenIntentAllowed) {
        Calls::openFullScreenSettings();   // Android 14+ special access
    }
});
```

| Field | Android | iOS |
| --- | --- | --- |
| `notificationsEnabled` | `NotificationManager.areNotificationsEnabled()` | Notification authorization is authorized or provisional |
| `fullScreenIntentAllowed` | `true` below Android 14, `canUseFullScreenIntent()` on 14+ | `true` unless the device region is mainland China (CallKit unavailable) |
| `callStyleSupported` | Android 12+ (older versions get a regular high-priority call notification) | Same as `fullScreenIntentAllowed` |

`canRing()` is `notificationsEnabled && fullScreenIntentAllowed`.
`openFullScreenSettings()` opens the Android 14+ "full screen notifications"
page for the app, the app notification settings on older Android versions, and
the app settings on iOS.

## Use with WebRTC

`pam-native-calls` shows the surfaces; `pam-native-webrtc` moves the media.
On Android start the ongoing call when the peer connection connects and end it
when you hang up. On iOS, CallKit owns the audio session: start call audio
(`CallAudio::start()`) only after CallKit posts
`Notification.Name.pamCallAudioActivated` (Swift), which `pam-native-webrtc`
observes.

## A real example: Zé Chat

Zé Chat registers one mapping that matches its backend push contract
(`type = 2` call, `call_type` 1 voice / 2 video / 3 group, `call_event = 1`
ended) and routes every action into its navigator:

```php
use Pam\Native\Calls\{CallAction, CallActionKind, Calls, IncomingCall, PushCallMapping};

final class CallSurfaces
{
    public static function register(Closure $onAction): void
    {
        Calls::fromPush(
            PushCallMapping::type(2)
                ->id('call_id')
                ->caller('notification_title', 'notification_image_url')
                ->subtitle('notification_body')
                ->video('call_type', 2, 3)
                ->endedWhen('call_event', 1)
                ->timeout(45),
        );
        Calls::onAction($onAction);
    }

    // Foreground ring (a realtime "call.started" event while the app is open).
    public static function showIncoming(string $callId, int $callType, string $title, string $avatarUrl): void
    {
        try {
            Calls::incoming(
                IncomingCall::make($callId)
                    ->caller($title, $avatarUrl !== '' ? $avatarUrl : null)
                    ->video(in_array($callType, [2, 3], true))
                    ->timeout(45)
                    ->data(['call_id' => $callId, 'call_type' => $callType, 'notification_title' => $title]),
            )->show();
        } catch (InvalidArgumentException) {
            // A malformed id or oversized title must never break the call screen.
        }
    }
}

// Navigation layer
CallSurfaces::register(function (CallAction $action): void {
    $callType = (int) ($action->data['call_type'] ?? ($action->video ? 2 : 1));
    match ($action->kind) {
        CallActionKind::Accept => $this->openCall($action->callId, $callType, accepted: true),
        CallActionKind::Open => $this->openCall($action->callId, $callType),
        CallActionKind::Decline, CallActionKind::Timeout => $this->api->reject($action->callId),
        CallActionKind::HangUp => $this->api->leave($action->callId),
    };
});

// Call screen: connected → ongoing surface; hang up → remove every surface.
Calls::ongoing($callId)->caller($title)->video($isVideo)->since($connectedAtMs)->show();
Calls::end($callId);
```

A runnable minimal app is in [`example/`](example).

## API reference

All classes live in `Pam\Native\Calls`.

### `Calls` (static facade, module `calls`)

| Method | Description |
| --- | --- |
| `incoming(IncomingCall $call): IncomingCall` | Returns the call; call `->show()` on it. |
| `ongoing(string $id): OngoingCall` | Starts an ongoing-call builder for `$id`. |
| `end(string $id, ?Closure $done = null): void` | Removes every surface of the call. Emits no action. `$done(bool $ok, string $error)`. |
| `endAll(?Closure $done = null): void` | Ends every known call. |
| `onAction(Closure $listener): int` | Registers `Closure(CallAction): void`; returns a listener id. Starts the native action long-poll on first use. |
| `offAction(int $listener): void` | Removes a listener. |
| `fromPush(PushCallMapping ...$mappings): void` | Persists push mappings natively, replacing earlier ones. |
| `readiness(Closure $done): void` | `Closure(CallReadiness): void`. Failures report all fields as `false`. |
| `voipToken(Closure $done): void` | `Closure(?string): void`; hex PushKit token on iOS, `null` on Android. |
| `openFullScreenSettings(): void` | Opens the full-screen-intent (or notification) settings. |
| `MODULE` | `'calls'`, the native module name. |

`dispatch()`, `reset()`, `send()`, `validId()`, `text()` and `avatar()` are
`@internal`.

### `IncomingCall` (immutable builder)

| Method | Rules |
| --- | --- |
| `make(string $id)` | Id: `[A-Za-z0-9_.:-]{1,128}`. |
| `caller(string $name, ?string $avatar = null)` | Name ≤ 256 characters (required before `show()`). Avatar: `http(s)` URL ≤ 2048 bytes or a relative sandbox path (no `/`, `..` or scheme). |
| `video(bool $video = true)` | Video call (icon, CallKit `hasVideo`, foreground service type). |
| `timeout(int $seconds)` | 5–300 seconds, default 45. Then a `Timeout` action. |
| `subtitle(string $subtitle)` | ≤ 256 characters. |
| `deepLink(string $url)` | ≤ 2048 characters. Opened on Accept/Open and routed by the core deep-link handling. |
| `labels(string $accept, string $decline)` | ≤ 64 characters each. |
| `data(array $data)` | String-keyed scalar map, returned in every `CallAction`. |
| `show(?Closure $done = null): self` | Sends the call. `$done(bool $ok, string $error)`. |
| `toWire(): array` | Wire payload (used by `show()`). |
| `$id` | Readonly call id. |

### `OngoingCall` (immutable builder)

`make(string $id)`, `since(DateTimeInterface|int $start)` (values below
`100_000_000_000` are seconds, larger values milliseconds; default: now),
`caller()`, `video()`, `subtitle()`, `deepLink()`, `hangUpLabel(string $label)`
(≤ 64), `show(?Closure $done = null)`, `toWire()`, readonly `$id`. Empty fields
fall back to the values of the incoming call with the same id.

### `PushCallMapping` (immutable builder)

| Method | Description |
| --- | --- |
| `type(string\|int $value, string $field = 'type')` | Matches pushes whose `$field` equals `$value`. |
| `types(array $values, string $field = 'type')` | Matches any of 1–16 values. |
| `id(string $field)` | Field holding the call id (default `call_id`). |
| `caller(string $nameField, ?string $avatarField = null)` | Name field (default `caller_name`) and optional avatar field. |
| `video(string $field, string\|int ...$values)` | Video when the field equals one of the values (`1`, `true`, `video` when none given). |
| `endedWhen(string $field, string\|int ...$values)` | Clears the call when the field equals one of the values (at least one). |
| `subtitle(string $field)`, `deepLink(string $field)` | Optional fields. |
| `timeout(int $seconds)` | 5–300, default 45. |
| `labels(string $accept, string $decline)` | Button labels. |
| `toArray(): array` | Persisted form. |

Field names must match `[A-Za-z0-9_.-]{1,64}`.

### `CallAction` (readonly)

`kind: CallActionKind`, `callId: string`, `video: bool`, `deepLink: string`,
`data: array` (the `IncomingCall::data()` map, or the push data for
push-driven calls), `atMillis: int` (when the action happened, Unix ms).
`fromWire(array $values): ?self` decodes a native record.

### `CallReadiness` (readonly)

`notificationsEnabled`, `fullScreenIntentAllowed`, `callStyleSupported`,
`canRing(): bool`.

### `CallActionKind` (int enum)

`Accept = 1`, `Decline = 2`, `Open = 3`, `HangUp = 4`, `Timeout = 5`.

### Errors

Builders throw `InvalidArgumentException` for invalid ids, field names,
oversized text, avatars outside the sandbox, a timeout outside 5–300 s,
non-scalar data, an `endedWhen()` without values and `types()` outside 1–16
values. Native failures (for example "Could not show incoming call", a CallKit
error description) are reported through the optional `$done(false, $message)`
callbacks; they never throw.

## Limits and troubleshooting

- **The phone does not ring in the background (Android):** the push must be an
  FCM *data* message with high priority. Check `Calls::readiness()`:
  notifications must be enabled and, on Android 14+, the full-screen intent
  allowed.
- **No full-screen UI on the lock screen:** Android 14+ requires the
  full-screen intent grant; call `Calls::openFullScreenSettings()` from a
  settings screen. Below Android 12 the call is a regular high-priority
  notification (no CallStyle).
- **Foreground service crash on Android 14+ when a call connects:** request
  `CAMERA` and `RECORD_AUDIO` before `Calls::ongoing()->show()`.
- **iOS app killed after a VoIP push:** every VoIP push must report a call. The
  plugin already reports and ends unmatched pushes; do not send VoIP pushes for
  anything else.
- **No CallKit in mainland China:** `readiness()` reports
  `fullScreenIntentAllowed = false`; use regular notifications there.
- **Actions arrive late:** actions taken while PHP was suspended are delivered
  when the runtime resumes and a listener is registered. Register
  `Calls::onAction()` at boot, not on a call screen.
- **iOS validation:** the iOS implementation mirrors the Android test suite
  (`ios/Tests/CallsTests.swift`) but has not been validated on a device yet.

## Compatibility

| `pushinbr/pam-native-calls` | `pushinbr/pam-native` | Android | iOS |
| --- | --- | --- | --- |
| 0.2.x | `>=1.0.35 <2.0.0` (tested with 1.14.x) | API 26+ (CallStyle on 31+) | 15+ (CallKit, PushKit) |
| 0.1.x | `>=1.0.35 <2.0.0` | API 26+ | Not supported |

## Tests

```bash
pam tests/run.php
cd android && ../../pam-native/android/gradlew -p . connectedDebugAndroidTest
```

The PHP suite covers builders, validation and the action queue. The Android
instrumented suite verifies CallStyle incoming/ongoing notifications, the
full-screen intent and ringtone channel, Decline/Timeout/Accept/HangUp flows,
the lock-screen UI, the foreground service, push mapping (show and end) and
the persistent action queue. `ios/Tests/CallsTests.swift` mirrors it with
XCTest.

## License

Apache-2.0
