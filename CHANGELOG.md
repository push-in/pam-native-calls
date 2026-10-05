# Changelog

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
