<?php

declare(strict_types=1);

namespace Pam\Native\Calls;

use Closure;
use InvalidArgumentException;

/** Immutable description of a ringing call shown with Notification.CallStyle and a full-screen UI. */
final class IncomingCall
{
    private string $callerName = '';
    private string $avatar = '';
    private bool $video = false;
    private int $timeoutSeconds = 45;
    private string $subtitle = '';
    private string $deepLink = '';
    private string $acceptLabel = '';
    private string $declineLabel = '';

    /** @var array<string, string|int|float|bool> */
    private array $data = [];

    private function __construct(public readonly string $id)
    {
    }

    public static function make(string $id): self
    {
        return new self(Calls::validId($id));
    }

    /** @param string|null $avatar https URL or a path relative to the PAM file sandbox. */
    public function caller(string $name, ?string $avatar = null): self
    {
        $copy = clone $this;
        $copy->callerName = Calls::text($name, 256);
        $copy->avatar = Calls::avatar($avatar ?? '');

        return $copy;
    }

    public function video(bool $video = true): self
    {
        $copy = clone $this;
        $copy->video = $video;

        return $copy;
    }

    /** Seconds before the call stops ringing and a Timeout action is emitted. */
    public function timeout(int $seconds): self
    {
        if ($seconds < 5 || $seconds > 300) {
            throw new InvalidArgumentException('Ring timeout must be between 5 and 300 seconds.');
        }
        $copy = clone $this;
        $copy->timeoutSeconds = $seconds;

        return $copy;
    }

    /** Secondary line; defaults to the localized "Incoming voice/video call". */
    public function subtitle(string $subtitle): self
    {
        $copy = clone $this;
        $copy->subtitle = Calls::text($subtitle, 256);

        return $copy;
    }

    /** URL opened in the app on Accept/Open, routed by the core deep link handling. */
    public function deepLink(string $url): self
    {
        $copy = clone $this;
        $copy->deepLink = Calls::text($url, 2048);

        return $copy;
    }

    public function labels(string $accept, string $decline): self
    {
        $copy = clone $this;
        $copy->acceptLabel = Calls::text($accept, 64);
        $copy->declineLabel = Calls::text($decline, 64);

        return $copy;
    }

    /** @param array<string, string|int|float|bool> $data Returned untouched in every CallAction. */
    public function data(array $data): self
    {
        foreach ($data as $key => $value) {
            if (!is_string($key) || !is_scalar($value)) {
                throw new InvalidArgumentException('Call data must be a string-keyed scalar map.');
            }
        }
        $copy = clone $this;
        $copy->data = $data;

        return $copy;
    }

    /** @param (Closure(bool, string): void)|null $done */
    public function show(?Closure $done = null): self
    {
        Calls::send('showIncoming', $this->toWire(), $done);

        return $this;
    }

    /** @return array<string, string|int|bool> */
    public function toWire(): array
    {
        if ($this->callerName === '') {
            throw new InvalidArgumentException('Incoming calls need a caller name.');
        }

        return [
            'callId' => $this->id,
            'name' => $this->callerName,
            'avatar' => $this->avatar,
            'video' => $this->video,
            'timeoutMillis' => $this->timeoutSeconds * 1000,
            'subtitle' => $this->subtitle,
            'deepLink' => $this->deepLink,
            'acceptLabel' => $this->acceptLabel,
            'declineLabel' => $this->declineLabel,
            'dataJson' => json_encode((object) $this->data, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE),
        ];
    }
}
