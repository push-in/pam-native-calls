<?php

declare(strict_types=1);

namespace Pam\Native\Calls;

/** Action taken on a call surface. Actions taken while PHP was suspended are queued natively and delivered on resume. */
final readonly class CallAction
{
    /** @param array<string, mixed> $data */
    public function __construct(
        public CallActionKind $kind,
        public string $callId,
        public bool $video = false,
        public string $deepLink = '',
        public array $data = [],
        public int $atMillis = 0,
    ) {
    }

    /** @param array<string, string|int|float|bool> $values */
    public static function fromWire(array $values): ?self
    {
        $kind = CallActionKind::tryFrom((int) ($values['kind'] ?? 0));
        $callId = (string) ($values['callId'] ?? '');
        if ($kind === null || $callId === '') {
            return null;
        }
        $data = json_decode((string) ($values['dataJson'] ?? '{}'), true);

        return new self(
            kind: $kind,
            callId: $callId,
            video: (bool) ($values['video'] ?? false),
            deepLink: (string) ($values['deepLink'] ?? ''),
            data: is_array($data) ? $data : [],
            atMillis: (int) ($values['at'] ?? 0),
        );
    }
}
