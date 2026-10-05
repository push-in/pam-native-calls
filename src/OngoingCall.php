<?php

declare(strict_types=1);

namespace Pam\Native\Calls;

use Closure;
use DateTimeInterface;

/**
 * Ongoing call surface: a CallStyle notification owned by a camera|microphone
 * foreground service, with a chronometer and a hang-up action. Keeps capture
 * alive while the app is in the background and the app visible over the lock
 * screen until the call ends.
 */
final class OngoingCall
{
    private int $sinceMillis;
    private string $callerName = '';
    private string $avatar = '';
    private bool $video = false;
    private string $subtitle = '';
    private string $deepLink = '';
    private string $hangUpLabel = '';

    private function __construct(public readonly string $id)
    {
        $this->sinceMillis = (int) floor(microtime(true) * 1000);
    }

    public static function make(string $id): self
    {
        return new self(Calls::validId($id));
    }

    /** Call start as a DateTime, Unix seconds or Unix milliseconds. */
    public function since(DateTimeInterface|int $start): self
    {
        $copy = clone $this;
        $copy->sinceMillis = match (true) {
            $start instanceof DateTimeInterface => (int) $start->format('Uv'),
            $start < 100_000_000_000 => $start * 1000,
            default => $start,
        };

        return $copy;
    }

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

    public function subtitle(string $subtitle): self
    {
        $copy = clone $this;
        $copy->subtitle = Calls::text($subtitle, 256);

        return $copy;
    }

    public function deepLink(string $url): self
    {
        $copy = clone $this;
        $copy->deepLink = Calls::text($url, 2048);

        return $copy;
    }

    public function hangUpLabel(string $label): self
    {
        $copy = clone $this;
        $copy->hangUpLabel = Calls::text($label, 64);

        return $copy;
    }

    /** @param (Closure(bool, string): void)|null $done */
    public function show(?Closure $done = null): self
    {
        Calls::send('showOngoing', $this->toWire(), $done);

        return $this;
    }

    /** @return array<string, string|int|bool> */
    public function toWire(): array
    {
        return [
            'callId' => $this->id,
            'name' => $this->callerName,
            'avatar' => $this->avatar,
            'video' => $this->video,
            'sinceMillis' => $this->sinceMillis,
            'subtitle' => $this->subtitle,
            'deepLink' => $this->deepLink,
            'hangUpLabel' => $this->hangUpLabel,
        ];
    }
}
