<?php

declare(strict_types=1);

namespace Pam\Native\Calls;

/** Whether the device will actually ring: notification permission, full-screen intent grant and CallStyle support. */
final readonly class CallReadiness
{
    public function __construct(
        public bool $notificationsEnabled,
        public bool $fullScreenIntentAllowed,
        public bool $callStyleSupported,
    ) {
    }

    public function canRing(): bool
    {
        return $this->notificationsEnabled && $this->fullScreenIntentAllowed;
    }
}
