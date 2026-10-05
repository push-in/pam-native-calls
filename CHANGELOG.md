# Changelog

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
