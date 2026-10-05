<?php

declare(strict_types=1);

$packageAutoload = dirname(__DIR__).'/vendor/autoload.php';
if (is_file($packageAutoload)) {
    require $packageAutoload;
}
$roots = [
    'Pam\\Native\\Calls\\' => dirname(__DIR__).'/src/',
    'Pam\\Native\\Testing\\' => dirname(__DIR__, 2).'/pam-native-testing/src/',
    'Pam\\Native\\' => dirname(__DIR__, 2).'/../pam-native/packages/native/src/',
];
spl_autoload_register(static function (string $class) use ($roots): void {
    foreach ($roots as $prefix => $root) {
        if (str_starts_with($class, $prefix)) {
            $file = $root.str_replace('\\', '/', substr($class, strlen($prefix))).'.php';
            if (is_file($file)) {
                require $file;
            }

            return;
        }
    }
});

use Pam\Native\Calls\CallAction;
use Pam\Native\Calls\CallActionKind;
use Pam\Native\Calls\CallReadiness;
use Pam\Native\Calls\Calls;
use Pam\Native\Calls\IncomingCall;
use Pam\Native\Calls\PushCallMapping;
use Pam\Native\Internal\Wire;
use Pam\Native\Testing\DispatchMode;
use Pam\Native\Testing\NativeTestHarness;

$tests = [];
$test = static function (string $name, Closure $body) use (&$tests): void {
    $tests[$name] = $body;
};
$check = static function (bool $condition, string $message): void {
    if (!$condition) {
        throw new RuntimeException($message);
    }
};
$throws = static function (string $class, Closure $body) use ($check): void {
    try {
        $body();
    } catch (Throwable $error) {
        $check($error instanceof $class, 'expected '.$class.', got '.$error::class);

        return;
    }
    throw new RuntimeException("expected {$class}");
};
$test('action kinds keep the public integer contract', static function () use ($check): void {
    $values = array_map(static fn (CallActionKind $kind): int => $kind->value, CallActionKind::cases());
    $check($values === range(1, count($values)), 'not sequential');
    $check(CallActionKind::Accept->value === 1 && CallActionKind::Decline->value === 2 && CallActionKind::Open->value === 3 && CallActionKind::HangUp->value === 4, 'contract changed');
});

$test('incoming calls send the typed native payload', static function () use ($check): void {
    $transport = NativeTestHarness::install();
    $transport->succeed('calls', 'showIncoming');
    $ok = null;
    Calls::incoming(
        IncomingCall::make('call-42')->caller('Ana Souza', 'https://cdn.example.com/a.jpg')->video()->timeout(45)
            ->deepLink('pushin://call/call-42')->labels('Atender', 'Recusar')->data(['room' => 'r-1', 'group' => false]),
    )->show(static function (bool $success) use (&$ok): void {
        $ok = $success;
    });
    $payload = Wire::decodeMap($transport->lastCall()->payload);
    $check($ok === true, 'callback');
    $check($payload['callId'] === 'call-42' && $payload['name'] === 'Ana Souza' && $payload['video'] === true, 'identity');
    $check($payload['timeoutMillis'] === 45000 && $payload['acceptLabel'] === 'Atender', 'timeout/labels');
    $check(json_decode($payload['dataJson'], true) === ['room' => 'r-1', 'group' => false], 'data');
    NativeTestHarness::uninstall();
});

$test('incoming call validation rejects unsafe input', static function () use ($throws): void {
    $throws(InvalidArgumentException::class, static fn () => IncomingCall::make('../etc'));
    $throws(InvalidArgumentException::class, static fn () => IncomingCall::make('ok')->timeout(2));
    $throws(InvalidArgumentException::class, static fn () => IncomingCall::make('ok')->caller('A', '/data/data/x.png'));
    $throws(InvalidArgumentException::class, static fn () => IncomingCall::make('ok')->caller('A', 'avatars/../../x.png'));
    $throws(InvalidArgumentException::class, static fn () => IncomingCall::make('ok')->toWire());
    $throws(InvalidArgumentException::class, static fn () => IncomingCall::make('ok')->data(['nested' => ['no']]));
});

$test('ongoing calls normalize the start time', static function () use ($check): void {
    $transport = NativeTestHarness::install();
    $transport->succeed('calls', 'showOngoing')->succeed('calls', 'showOngoing')->succeed('calls', 'end');
    Calls::ongoing('call-42')->since(1_700_000_000)->caller('Ana')->video()->show();
    $seconds = Wire::decodeMap($transport->lastCall()->payload);
    Calls::ongoing('call-42')->since(new DateTimeImmutable('@1700000000.250'))->show();
    $dateTime = Wire::decodeMap($transport->lastCall()->payload);
    Calls::end('call-42');
    $check($seconds['sinceMillis'] === 1_700_000_000_000 && $seconds['video'] === true, 'unix seconds');
    $check($dateTime['sinceMillis'] === 1_700_000_000_250, 'datetime millis');
    $check($transport->lastCall()->method === 'end', 'end');
    NativeTestHarness::uninstall();
});

$test('actions are delivered through the re-armed native channel', static function () use ($check): void {
    Calls::reset();
    $transport = NativeTestHarness::install();
    $transport->succeed('calls', 'next', ['kind' => 1, 'callId' => 'c1', 'video' => true, 'deepLink' => 'pushin://call/c1', 'dataJson' => '{"room":"r"}', 'at' => 5], DispatchMode::Deferred)
        ->succeed('calls', 'next', ['kind' => 4, 'callId' => 'c1', 'dataJson' => '{}'], DispatchMode::Deferred)
        ->succeed('calls', 'next', [], DispatchMode::Deferred);
    $actions = [];
    $first = Calls::onAction(static function (CallAction $action) use (&$actions): void {
        $actions[] = $action;
    });
    Calls::onAction(static fn (CallAction $action): null => null);
    $transport->assertCalled('calls', 'next', 1);
    $transport->flushOne();
    $transport->flushOne();
    $check(count($actions) === 2 && $actions[0]->kind === CallActionKind::Accept && $actions[0]->video, 'accept');
    $check($actions[0]->data === ['room' => 'r'] && $actions[0]->deepLink === 'pushin://call/c1' && $actions[0]->atMillis === 5, 'payload');
    $check($actions[1]->kind === CallActionKind::HangUp, 'hang up');
    $transport->assertCalled('calls', 'next', 3);
    Calls::offAction($first);
    Calls::reset();
    NativeTestHarness::uninstall();
});

$test('push mappings serialize field selectors for the native receiver', static function () use ($check, $throws): void {
    $transport = NativeTestHarness::install();
    $transport->succeed('calls', 'configurePush');
    Calls::fromPush(
        PushCallMapping::type('call.incoming')->id('call_id')->caller('caller_name', 'caller_avatar')
            ->video('call_type', 2, 3)->endedWhen('call_event', 1)->timeout(30)->labels('Atender', 'Recusar'),
        PushCallMapping::types([2, 'group'], 'kind'),
    );
    $mappings = json_decode(Wire::decodeMap($transport->lastCall()->payload)['mappingsJson'], true);
    $check($mappings[0]['typeValues'] === ['call.incoming'] && $mappings[0]['avatarField'] === 'caller_avatar', 'fields');
    $check($mappings[0]['videoValues'] === ['2', '3'] && $mappings[0]['endedValues'] === ['1'] && $mappings[0]['timeoutMillis'] === 30000, 'values');
    $check($mappings[1]['typeField'] === 'kind' && $mappings[1]['typeValues'] === ['2', 'group'] && $mappings[1]['idField'] === 'call_id', 'defaults');
    $throws(InvalidArgumentException::class, static fn () => PushCallMapping::type('x', 'bad field'));
    NativeTestHarness::uninstall();
});

$test('readiness maps native capability flags', static function () use ($check): void {
    $transport = NativeTestHarness::install();
    $transport->succeed('calls', 'readiness', ['notificationsEnabled' => true, 'fullScreenIntentAllowed' => false, 'callStyleSupported' => true]);
    $readiness = null;
    Calls::readiness(static function (CallReadiness $value) use (&$readiness): void {
        $readiness = $value;
    });
    $check($readiness?->notificationsEnabled === true && !$readiness->canRing() && $readiness->callStyleSupported, 'readiness');
    NativeTestHarness::uninstall();
});

$test('android surfaces use CallStyle, full-screen intents and a camera|microphone service', static function () use ($check): void {
    $root = dirname(__DIR__).'/android/src/main';
    $notifier = (string) file_get_contents($root.'/kotlin/dev/pam/calls/CallNotifier.kt');
    $manifest = (string) file_get_contents($root.'/AndroidManifest.xml');
    $check(str_contains($notifier, 'CallStyle.forIncomingCall') && str_contains($notifier, 'CallStyle.forOngoingCall'), 'CallStyle');
    $check(str_contains($notifier, 'setFullScreenIntent'), 'full screen intent');
    $check(str_contains($manifest, 'android:foregroundServiceType="camera|microphone"'), 'service type');
    $check(str_contains($manifest, 'dev.pam.nativeapp.action.PUSH_RECEIVED'), 'push receiver');
    $plugin = json_decode((string) file_get_contents(dirname(__DIR__).'/pam-native.plugin.json'), true, flags: JSON_THROW_ON_ERROR);
    $check($plugin['pamNative'] === ['minimum' => '1.0.35', 'maximumExclusive' => '2.0.0'], 'range');
    $check(in_array('android.permission.USE_FULL_SCREEN_INTENT', $plugin['android']['permissions'], true), 'permission');
});

$failed = 0;
foreach ($tests as $name => $body) {
    try {
        $body();
        fwrite(STDOUT, "PASS {$name}\n");
    } catch (Throwable $error) {
        $failed++;
        fwrite(STDERR, "FAIL {$name}: {$error->getMessage()}\n");
    }
}
fwrite(STDOUT, count($tests)." tests, {$failed} failures\n");
exit($failed === 0 ? 0 : 1);
