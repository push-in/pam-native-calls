<?php

declare(strict_types=1);

namespace Pam\Native\Calls;

use InvalidArgumentException;

/**
 * Declares how a data push becomes an incoming call while PHP is suspended.
 *
 * The mapping is persisted natively; the plugin's push receiver evaluates it
 * for every data message, shows the call UI and clears it on "ended" pushes.
 * Values are compared as strings, so `2` matches both `"2"` and `2`.
 */
final class PushCallMapping
{
    private string $idField = 'call_id';
    private string $nameField = 'caller_name';
    private string $avatarField = '';
    private string $subtitleField = '';
    private string $deepLinkField = '';
    private string $videoField = '';

    /** @var list<string> */
    private array $videoValues = [];
    private string $endedField = '';

    /** @var list<string> */
    private array $endedValues = [];
    private int $timeoutSeconds = 45;
    private string $acceptLabel = '';
    private string $declineLabel = '';

    /** @param list<string> $typeValues */
    private function __construct(private readonly string $typeField, private readonly array $typeValues)
    {
    }

    public static function type(string|int $value, string $field = 'type'): self
    {
        return new self(self::field($field), [(string) $value]);
    }

    /** Matches any of several type values, e.g. `PushCallMapping::types(['call.incoming', 'call.group'])`. */
    public static function types(array $values, string $field = 'type'): self
    {
        if ($values === [] || count($values) > 16) {
            throw new InvalidArgumentException('Provide between one and sixteen type values.');
        }

        return new self(self::field($field), array_map('strval', array_values($values)));
    }

    public function id(string $field): self
    {
        return $this->with('idField', self::field($field));
    }

    public function caller(string $nameField, ?string $avatarField = null): self
    {
        $copy = $this->with('nameField', self::field($nameField));

        return $avatarField === null ? $copy : $copy->with('avatarField', self::field($avatarField));
    }

    /** The call is a video call when `$field` equals one of `$values` (or is truthy when no values are given). */
    public function video(string $field, string|int ...$values): self
    {
        $copy = $this->with('videoField', self::field($field));
        $copy->videoValues = $values === [] ? ['1', 'true', 'video'] : array_map('strval', array_values($values));

        return $copy;
    }

    /** Clears the call UI (no action) when `$field` equals one of `$values`, e.g. a "call ended" push. */
    public function endedWhen(string $field, string|int ...$values): self
    {
        if ($values === []) {
            throw new InvalidArgumentException('endedWhen() needs at least one value.');
        }
        $copy = $this->with('endedField', self::field($field));
        $copy->endedValues = array_map('strval', array_values($values));

        return $copy;
    }

    public function subtitle(string $field): self
    {
        return $this->with('subtitleField', self::field($field));
    }

    public function deepLink(string $field): self
    {
        return $this->with('deepLinkField', self::field($field));
    }

    public function timeout(int $seconds): self
    {
        if ($seconds < 5 || $seconds > 300) {
            throw new InvalidArgumentException('Ring timeout must be between 5 and 300 seconds.');
        }

        return $this->with('timeoutSeconds', $seconds);
    }

    public function labels(string $accept, string $decline): self
    {
        return $this->with('acceptLabel', Calls::text($accept, 64))->with('declineLabel', Calls::text($decline, 64));
    }

    /** @return array<string, string|int|list<string>> */
    public function toArray(): array
    {
        return [
            'typeField' => $this->typeField,
            'typeValues' => $this->typeValues,
            'idField' => $this->idField,
            'nameField' => $this->nameField,
            'avatarField' => $this->avatarField,
            'subtitleField' => $this->subtitleField,
            'deepLinkField' => $this->deepLinkField,
            'videoField' => $this->videoField,
            'videoValues' => $this->videoValues,
            'endedField' => $this->endedField,
            'endedValues' => $this->endedValues,
            'timeoutMillis' => $this->timeoutSeconds * 1000,
            'acceptLabel' => $this->acceptLabel,
            'declineLabel' => $this->declineLabel,
        ];
    }

    private function with(string $property, string|int $value): self
    {
        $copy = clone $this;
        $copy->{$property} = $value;

        return $copy;
    }

    private static function field(string $field): string
    {
        if (preg_match('/^[A-Za-z0-9_.\-]{1,64}$/D', $field) !== 1) {
            throw new InvalidArgumentException("Invalid push data field: {$field}");
        }

        return $field;
    }
}
