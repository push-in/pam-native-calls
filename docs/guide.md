# PAM Native Calls: guide

> Moved out of the README; nothing was removed. Portuguese button labels in the examples were translated to English.

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
        ->labels('Answer', 'Decline')       // defaults are localized (en, pt)
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

### Declining while PHP is suspended

A Decline tapped on the notification or the lock-screen UI while the PHP
runtime is suspended (or the process was only woken by the push) reaches PHP
when the runtime resumes, which can be long after the caller should have
stopped ringing. Give the call a decline endpoint and the plugin tells the
server itself:

```php
IncomingCall::make($callId)->caller($name)->declineEndpoint("https://api.example.com/calls/{$callId}/decline", $declineToken)->show();
// push-driven calls: fields of the push data
PushCallMapping::type('call')->declineEndpoint('decline_url', 'decline_token');
```

On Decline the plugin sends `POST <url>` with `{"call_id": "...", "action":
"decline"}` and `Authorization: Bearer <token>` (Android: inside the
notification broadcast; iOS: inside a background task after CallKit's end
action), then queues the `Decline` action with `serverNotified` true when the
server answered 2xx. Issue a short-lived token valid only for that call and
that action. When `serverNotified` is true the app skips its own request.

## Lock screen

Accepting from the lock screen keeps the app's activities visible over the
keyguard (and turns the screen on) while the call is accepted or ongoing. Apps
usually remove the ringing surfaces on accept (`Calls::end()`) and publish the
ongoing call once media connects: the accepted call keeps the app over the lock
screen for 30 s after the acceptance for that gap, and `showOngoing()` keeps it
until the call ends. Publish `Calls::ongoing()` as soon as the call is
connecting so a slow connection does not drop behind the keyguard; ending
another call never releases the accepted one.

## Picture-in-picture (Android)

```php
// Video call with remote video on screen: arm it (Android 12+ enters on Home/Recents).
Calls::pictureInPicture(true, 9, 16);
// A "minimize" button (Android 8+; the only way in below Android 12).
Calls::enterPictureInPicture();
// Hide the call controls while the app is the floating window.
Calls::onPictureInPicture(fn (bool $active) => $this->compact = $active);
// Video stopped or the call ended (the plugin also disarms once no call is left).
Calls::pictureInPicture(false);
```

The host Activity (PamActivity) must declare
`android:supportsPictureInPicture="true"`; PamActivity already handles size
changes in place. `CallReadiness::$pictureInPictureSupported` tells whether the
device supports it. iOS: not available (CallKit owns the system call UI).

## Ongoing call

```php
Calls::ongoing($callId)
    ->since($connectedAt)        // DateTimeInterface, Unix seconds or Unix milliseconds
    ->caller($name, $avatar)     // falls back to the incoming call's values
    ->video()
    ->subtitle('Tap to return to the call')
    ->hangUpLabel('Hang up')
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
        ->labels('Answer', 'Decline'),
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

A runnable minimal app is in [`example/`](../example).
