# PAM Native Calls: API reference

> Moved out of the README; nothing was removed.

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
