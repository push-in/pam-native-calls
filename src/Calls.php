<?php

declare(strict_types=1);

namespace Pam\Native\Calls;

use Closure;
use InvalidArgumentException;
use Pam\Native\Modules\NativeModuleResult;
use Pam\Native\Modules\NativeModules;

/**
 * System call surfaces: incoming CallStyle notifications with a full-screen
 * lock-screen UI, ongoing call foreground service, call actions and push
 * mappings evaluated natively while PHP is suspended.
 */
final class Calls
{
    public const string MODULE = 'calls';

    /** @var array<int, Closure(CallAction): void> */
    private static array $listeners = [];
    private static int $nextListener = 1;
    private static bool $listening = false;

    private function __construct()
    {
    }

    public static function incoming(IncomingCall $call): IncomingCall
    {
        return $call;
    }

    public static function ongoing(string $id): OngoingCall
    {
        return OngoingCall::make($id);
    }

    /** Removes every surface of the call (ringing UI, notification, foreground service). Emits no action. */
    public static function end(string $id, ?Closure $done = null): void
    {
        self::send('end', ['callId' => self::validId($id)], $done);
    }

    public static function endAll(?Closure $done = null): void
    {
        self::send('endAll', [], $done);
    }

    /**
     * Registers a call action listener. Actions taken while PHP was suspended
     * (notification buttons, lock-screen UI, timeouts) are delivered on resume.
     *
     * @param Closure(CallAction): void $listener
     */
    public static function onAction(Closure $listener): int
    {
        $id = self::$nextListener++;
        self::$listeners[$id] = $listener;
        if (!self::$listening) {
            self::$listening = true;
            self::listen();
        }

        return $id;
    }

    public static function offAction(int $listener): void
    {
        unset(self::$listeners[$listener]);
    }

    /** Persists push mappings natively; replaces earlier mappings. Call once at boot. */
    public static function fromPush(PushCallMapping ...$mappings): void
    {
        self::send('configurePush', [
            'mappingsJson' => json_encode(
                array_map(static fn (PushCallMapping $mapping): array => $mapping->toArray(), $mappings),
                JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE,
            ),
        ]);
    }

    /** @param Closure(CallReadiness): void $done */
    public static function readiness(Closure $done): void
    {
        NativeModules::call(self::MODULE, 'readiness', [], static function (NativeModuleResult $result) use ($done): void {
            $values = $result->succeeded() ? $result->values() : [];
            $done(new CallReadiness(
                (bool) ($values['notificationsEnabled'] ?? false),
                (bool) ($values['fullScreenIntentAllowed'] ?? false),
                (bool) ($values['callStyleSupported'] ?? false),
            ));
        });
    }

    /** Opens the Android 14+ "full screen notifications" setting (notification settings on older versions). */
    public static function openFullScreenSettings(): void
    {
        self::send('openFullScreenSettings', []);
    }

    /** @internal */
    public static function dispatch(CallAction $action): void
    {
        foreach (self::$listeners as $listener) {
            $listener($action);
        }
    }

    /** @internal Used by tests to reset static state. */
    public static function reset(): void
    {
        self::$listeners = [];
        self::$listening = false;
    }

    /**
     * @internal
     * @param array<string, string|int|float|bool> $values
     * @param (Closure(bool, string): void)|null $done
     */
    public static function send(string $method, array $values, ?Closure $done = null): void
    {
        NativeModules::call(self::MODULE, $method, $values, static function (NativeModuleResult $result) use ($done): void {
            if ($done !== null) {
                $done($result->succeeded(), $result->succeeded() ? '' : $result->message());
            }
        });
    }

    /** @internal */
    public static function validId(string $id): string
    {
        if (preg_match('/^[A-Za-z0-9_.:\-]{1,128}$/D', $id) !== 1) {
            throw new InvalidArgumentException('Call ids may contain letters, digits, ".", ":", "-" and "_" (max 128).');
        }

        return $id;
    }

    /** @internal */
    public static function text(string $value, int $max): string
    {
        $value = trim($value);
        if (strlen($value) > $max * 4 || mb_strlen($value) > $max) {
            throw new InvalidArgumentException("Text exceeds {$max} characters.");
        }

        return $value;
    }

    /** @internal */
    public static function avatar(string $avatar): string
    {
        if ($avatar === '') {
            return '';
        }
        $isUrl = preg_match('#^https?://#i', $avatar) === 1;
        if (strlen($avatar) > 2048 || (!$isUrl && (str_starts_with($avatar, '/') || preg_match('#(^|/)\.\.(/|$)#', $avatar) === 1 || str_contains($avatar, '://')))) {
            throw new InvalidArgumentException('Avatars must be http(s) URLs or relative sandbox paths.');
        }

        return $avatar;
    }

    private static function listen(): void
    {
        NativeModules::call(self::MODULE, 'next', [], static function (NativeModuleResult $result): void {
            if (!$result->succeeded() || self::$listeners === []) {
                self::$listening = false;

                return;
            }
            $action = CallAction::fromWire($result->values());
            if ($action !== null) {
                self::dispatch($action);
            }
            self::listen();
        });
    }
}
