# Changelog

## 0.2.4 - 2026-11-18

- Fixed: accepting from the lock screen no longer drops the app behind the keyguard when the app clears the ringing UI before showOngoing (30 s grace; other calls ending never release the accepted one).
- Fixed: CallForegroundService always honours the startForegroundService() contract (no "did not then call startForeground" crash); Android 14+ skips the service without a granted type.
- Fixed: showOngoing no longer resurrects a call ended while its avatar downloaded.
- Changed: incoming calls ring immediately; the avatar is fetched afterwards and reposted silently.
- Changed: the full-screen incoming call UI scrolls and respects system bars (landscape phones).
- Added: IncomingCall::declineEndpoint(), PushCallMapping::declineEndpoint() and CallAction::$serverNotified: native decline delivery while PHP is suspended.
- Added: Android picture-in-picture: Calls::pictureInPicture(), enterPictureInPicture(), onPictureInPicture() and CallReadiness::$pictureInPictureSupported (the app declares android.pictureInPicture, PAM Native 1.54.0+).
- Added: Spanish and French strings.

## 0.2.3 - 2026-11-18

- Tests: the PAM Native range check follows the requirement in composer.json
  (the manifest minimum must equal it, below 2.0.0) instead of a hardcoded
  version. The previous release raised the requirement to 1.53.1 and kept the
  old expectation, so `composer test` failed and blocked the PAM Native
  release certification.

## 0.2.2 - 2026-11-17

- Android 7.0/7.1 support: the plugin declares minSdk 24 and every newer Android API it uses has a version check and fallback. Requires PAM Native 1.53.1 or later.

## 0.2.1 - 2026-11-16

- An ended call never rings again: `Calls::end()`, Decline, HangUp, the ring
  timeout and "ended" pushes remember the call id (persisted, 1 h, at most 64
  ids), and a later `showIncoming` or incoming push for it is ignored (iOS
  VoIP pushes still report and end a call). Fixes a received call push
  replayed to PHP on app launch re-posting the ringing notification of a call
  that had already ended, and an "ended" push racing the avatar download.

## 0.2.0 - 2026-10-05

- iOS: CallKit incoming and ongoing calls (system call UI, ring timeout,
  outgoing calls registered with `CXStartCallAction`), Accept/Decline/HangUp/
  Timeout delivered through the same durable action queue as Android.
- iOS: PushKit VoIP ringing from `Calls::fromPush()` mappings while PHP is
  suspended; unmatched/ended VoIP pushes report and end a call as iOS requires.
- Add `Calls::voipToken()` (iOS PushKit token; `null` on Android).
- The plugin declares `voip`/`audio`/`remote-notification` background modes and
  the `aps-environment` entitlement for iOS.
- XCTest mirror of the Android suite (`ios/Tests`). Uncompiled on iOS; needs
  device validation.

## 0.1.0 - 2026-10-05

- Add `Calls::incoming(IncomingCall)` with `Notification.CallStyle`, insistent
  ringtone channel, ring timeout and a native full-screen lock-screen UI.
- Add `Calls::ongoing()` backed by a `camera|microphone` foreground service with
  a chronometer and hang-up action; `Calls::end()` / `Calls::endAll()`.
- Add `Calls::onAction()` with `CallActionKind` Accept, Decline, Open, HangUp and
  Timeout, queued natively and delivered when PHP resumes.
- Add `Calls::fromPush(PushCallMapping)` so data pushes ring (and stop ringing)
  while the PHP runtime is suspended.
- Keep the app over the lock screen after Accept and during ongoing calls.
- Add `Calls::readiness()` and `Calls::openFullScreenSettings()`.
