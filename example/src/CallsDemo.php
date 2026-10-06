<?php

declare(strict_types=1);

namespace App;

use Pam\Native\Calls\CallAction;
use Pam\Native\Calls\CallActionKind;
use Pam\Native\Calls\CallReadiness;
use Pam\Native\Calls\Calls;
use Pam\Native\Calls\IncomingCall;
use Pam\Native\Calls\PushCallMapping;
use Pam\Native\Component;
use Pam\Native\Element;
use Pam\Native\PermissionDecision;
use Pam\Native\PermissionKind;
use Pam\Native\Style;
use Pam\Native\System\Permissions;
use Pam\Native\System\Timers;
use Pam\Native\UI\Button;
use Pam\Native\UI\Column;
use Pam\Native\UI\SafeAreaView;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Text;

final class CallsDemo extends Component
{
    private const string CALL_ID = 'demo-1';

    private string $readiness = 'Checking…';
    private bool $ongoing = false;

    /** @var list<string> */
    private array $log = [];

    public function boot(): void
    {
        // Ring from pushes while PHP is suspended (FCM data / APNs VoIP).
        Calls::fromPush(
            PushCallMapping::type('call.incoming')
                ->id('call_id')
                ->caller('caller_name', 'caller_avatar')
                ->video('call_type', 'video')
                ->endedWhen('call_event', 'ended')
                ->timeout(30),
        );
        Calls::onAction($this->onCallAction(...));
        Permissions::requestKind(PermissionKind::Notifications, function (PermissionDecision $_decision): void {
            $this->refreshReadiness();
        });
    }

    public function render(): Element
    {
        $lines = array_map(
            static fn (string $line): Text => Text::make($line)->style(new Style(fontSize: 13)),
            array_slice($this->log, 0, 12),
        );

        return Screen::make(
            SafeAreaView::make(
                Column::make(
                    Text::make('PAM Native Calls')->style(new Style(fontSize: 24, fontWeight: 700)),
                    Text::make('Readiness: '.$this->readiness),
                    Button::make('Ring in 5 s (lock the phone)')->onPress($this->ringSoon(...)),
                    $this->ongoing
                        ? Button::make('Hang up')->onPress($this->hangUp(...))
                        : Button::make('Start ongoing call')->onPress($this->startOngoing(...)),
                    Button::make('Full-screen settings')->onPress(static fn () => Calls::openFullScreenSettings()),
                    Text::make('Actions')->style(new Style(fontSize: 18, fontWeight: 600)),
                    ...$lines,
                )->style(new Style(flexGrow: 1, padding: 24, gap: 12)),
            ),
        );
    }

    public function ringSoon(): void
    {
        Timers::timeout(5_000, function (): void {
            Calls::incoming(
                IncomingCall::make(self::CALL_ID)
                    ->caller('Ana Souza')
                    ->video()
                    ->timeout(30)
                    ->data(['room' => 'demo-room']),
            )->show(function (bool $ok, string $error): void {
                $this->append($ok ? 'Ringing '.self::CALL_ID : 'Ring failed: '.$error);
            });
        });
    }

    public function startOngoing(): void
    {
        // The ongoing surface is a camera|microphone foreground service on Android.
        Permissions::requestKind(PermissionKind::Microphone, function (PermissionDecision $mic): void {
            Permissions::requestKind(PermissionKind::Camera, function (PermissionDecision $camera) use ($mic): void {
                if (!$mic->granted() || !$camera->granted()) {
                    $this->append('Camera and microphone are required for an ongoing call');

                    return;
                }
                Calls::ongoing(self::CALL_ID)->caller('Ana Souza')->video()->show();
                $this->ongoing = true;
            });
        });
    }

    public function hangUp(): void
    {
        Calls::end(self::CALL_ID);
        $this->ongoing = false;
        $this->append('Ended '.self::CALL_ID);
    }

    private function onCallAction(CallAction $action): void
    {
        $this->append(sprintf('%s %s %s', date('H:i:s', intdiv($action->atMillis, 1000)), $action->kind->name, $action->callId));
        match ($action->kind) {
            CallActionKind::Accept => $this->startOngoing(),
            CallActionKind::HangUp => $this->hangUp(),
            CallActionKind::Decline, CallActionKind::Timeout, CallActionKind::Open => null,
        };
    }

    private function refreshReadiness(): void
    {
        Calls::readiness(function (CallReadiness $readiness): void {
            $this->readiness = $readiness->canRing()
                ? 'ready to ring'
                : sprintf(
                    'notifications %s, full screen %s',
                    $readiness->notificationsEnabled ? 'on' : 'off',
                    $readiness->fullScreenIntentAllowed ? 'allowed' : 'denied',
                );
        });
    }

    private function append(string $line): void
    {
        array_unshift($this->log, $line);
    }
}
