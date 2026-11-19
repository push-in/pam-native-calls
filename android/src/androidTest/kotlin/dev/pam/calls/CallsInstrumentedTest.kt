package dev.pam.calls

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.modules.ModuleCompletion
import dev.pam.nativeapp.modules.ModuleResultStatus
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import dev.pam.nativeapp.push.BackgroundPush
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val module = CallsModule(context)

    private data class Result(val ok: Boolean, val values: Map<String, WireValue>, val message: String)

    @Before
    fun setUp() {
        listOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA).forEach {
            runCatching { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it) }
        }
        CallController.endAll(context)
        CallStore.clear(context)
        CallNotifier.manager(context).cancelAll()
    }

    @After
    fun tearDown() {
        CallController.endAll(context)
        CallStore.clear(context)
    }

    private fun call(method: String, values: Map<String, WireValue> = emptyMap()): Result {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result>()
        module.invoke(method, WireMap.encode(values), ModuleCompletion { status, payload ->
            result.set(
                if (status == ModuleResultStatus.SUCCESS) Result(true, WireMap.decode(payload), "") else Result(false, emptyMap(), String(payload)),
            )
            latch.countDown()
        })
        assertTrue("$method timed out", latch.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    private fun incoming(id: String, timeoutMillis: Long = 45_000, video: Boolean = true) = call(
        "showIncoming",
        mapOf(
            "callId" to WireValue.Text(id),
            "name" to WireValue.Text("Ana Souza"),
            "avatar" to WireValue.Text(""),
            "video" to WireValue.Flag(video),
            "timeoutMillis" to WireValue.Integer(timeoutMillis),
            "subtitle" to WireValue.Text(""),
            "deepLink" to WireValue.Text("pushin://call/$id"),
            "acceptLabel" to WireValue.Text("Atender"),
            "declineLabel" to WireValue.Text("Recusar"),
            "dataJson" to WireValue.Text("""{"room":"r-1"}"""),
        ),
    ).also { assertTrue(it.message, it.ok) }

    private fun notification(id: String): Notification? =
        CallNotifier.manager(context).activeNotifications.firstOrNull { it.id == CallNotifier.notificationId(id) }?.notification

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue("Condition not met within ${timeoutMillis}ms", condition())
    }

    private fun nextAction(): Map<String, WireValue> = call("next").also { assertTrue(it.message, it.ok) }.values

    private fun kind(values: Map<String, WireValue>) = (values["kind"] as WireValue.Integer).value

    @Test
    fun incomingCallUsesCallStyleFullScreenIntentAndRingtoneChannel() {
        incoming("in-1")
        waitUntil(3_000) { notification("in-1") != null }
        val posted = notification("in-1")!!
        assertEquals(Notification.CATEGORY_CALL, posted.category)
        assertNotNull(posted.fullScreenIntent)
        assertTrue(posted.flags and Notification.FLAG_INSISTENT != 0)
        assertTrue(posted.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(CallNotifier.CHANNEL_INCOMING, posted.channelId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertEquals(Notification.CallStyle.CALL_TYPE_INCOMING, posted.extras.getInt(Notification.EXTRA_CALL_TYPE))
            assertTrue(posted.extras.getBoolean(Notification.EXTRA_CALL_IS_VIDEO))
        }
        val channel = CallNotifier.manager(context).getNotificationChannel(CallNotifier.CHANNEL_INCOMING)
        assertEquals(android.app.NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNotNull(channel.sound)

        val readiness = call("readiness").values
        assertTrue((readiness["notificationsEnabled"] as WireValue.Flag).value)
        assertEquals(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S, (readiness["callStyleSupported"] as WireValue.Flag).value)
    }

    @Test
    fun declineActionIsQueuedWithCallDataAndClearsTheNotification() {
        incoming("in-2")
        waitUntil(3_000) { notification("in-2") != null }
        CallIntents.broadcast(context, "in-2", CallIntents.ACTION_DECLINE).send()
        waitUntil(3_000) { notification("in-2") == null && CallStore.pendingActions(context) == 1 }
        val action = nextAction()
        assertEquals(2L, kind(action))
        assertEquals("in-2", (action["callId"] as WireValue.Text).value)
        assertTrue((action["video"] as WireValue.Flag).value)
        assertEquals("pushin://call/in-2", (action["deepLink"] as WireValue.Text).value)
        assertEquals("r-1", JSONObject((action["dataJson"] as WireValue.Text).value).getString("room"))
        assertNull(CallStore.get(context, "in-2"))
    }

    @Test
    fun ringTimeoutEmitsTimeoutAction() {
        incoming("in-3", timeoutMillis = 5_000, video = false)
        waitUntil(3_000) { notification("in-3") != null }
        waitUntil(9_000) { CallStore.pendingActions(context) == 1 }
        assertNull(notification("in-3"))
        assertEquals(5L, kind(nextAction()))
    }

    @Test
    fun lockScreenUiAcceptsAndKeepsTheAppOverTheLockScreen() {
        incoming("in-4")
        val intent = Intent(context, IncomingCallActivity::class.java).putExtra(CallIntents.EXTRA_CALL_ID, "in-4")
        ActivityScenario.launch<IncomingCallActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val accept = activity.window.decorView.findViewWithTag<View>(IncomingCallActivity.TAG_ACCEPT)
                assertNotNull(accept)
                assertNotNull(activity.window.decorView.findViewWithTag<View>(IncomingCallActivity.TAG_DECLINE))
                accept.performClick()
            }
        }
        val action = nextAction()
        assertEquals(1L, kind(action))
        assertEquals("in-4", (action["callId"] as WireValue.Text).value)
        assertNull(notification("in-4"))
        assertTrue(LockScreenPolicy.isActive(context))
        // The app clears the ringing surfaces on accept, before media connects:
        // the accepted call keeps the app over the lock screen meanwhile.
        assertTrue(call("end", mapOf("callId" to WireValue.Text("in-4"))).ok)
        assertTrue(LockScreenPolicy.isActive(context))
        assertTrue(call("showOngoing", mapOf("callId" to WireValue.Text("in-4"), "name" to WireValue.Text("Ana"))).ok)
        assertTrue(LockScreenPolicy.isActive(context))
        assertTrue(call("end", mapOf("callId" to WireValue.Text("in-4"))).ok)
        assertFalse(LockScreenPolicy.isActive(context))
    }

    @Test
    fun acceptedCallSurvivesOtherCallsEndingAndExpiresAfterTheGrace() {
        incoming("acc-1")
        incoming("acc-2")
        CallController.accept(context, "acc-1", launch = false)
        assertTrue(call("end", mapOf("callId" to WireValue.Text("acc-2"))).ok)
        assertTrue(LockScreenPolicy.isActive(context))
        assertTrue(call("end", mapOf("callId" to WireValue.Text("acc-1"))).ok)
        assertTrue(CallStore.hasActiveCall(context))
        val now = System.currentTimeMillis()
        assertFalse(CallStore.hasActiveCall(context, now + CallStore.ACCEPT_GRACE_MILLIS + 1))
        assertTrue(call("endAll").ok)
        assertFalse(LockScreenPolicy.isActive(context))
    }

    @Test
    fun ringingStartsBeforeTheAvatarDownloads() {
        // Non-routable address: the avatar connect only times out (3 s).
        val started = System.currentTimeMillis()
        val pending = CountDownLatch(1)
        module.invoke(
            "showIncoming",
            WireMap.encode(
                mapOf(
                    "callId" to WireValue.Text("slow-1"),
                    "name" to WireValue.Text("Ana"),
                    "avatar" to WireValue.Text("https://10.255.255.1/avatar.png"),
                    "video" to WireValue.Flag(false),
                    "timeoutMillis" to WireValue.Integer(45_000),
                ),
            ),
            ModuleCompletion { _, _ -> pending.countDown() },
        )
        waitUntil(1_500) { notification("slow-1") != null }
        assertTrue("rang after ${System.currentTimeMillis() - started}ms", System.currentTimeMillis() - started < 1_500)
        assertTrue(pending.await(10, TimeUnit.SECONDS))
        assertNotNull(notification("slow-1"))
    }

    @Test
    fun foregroundServiceStartRacingEndNeverCrashes() {
        repeat(5) { index ->
            val id = "race-$index"
            CallStore.put(context, CallInfo(id = id, name = "Ana", ongoing = true))
            CallForegroundService.start(context, id)
            // end() before onStartCommand: the service must still call startForeground().
            CallController.end(context, id)
        }
        Thread.sleep(3_000)
        assertNull(CallForegroundService.foregroundCallId)
        assertTrue(CallNotifier.manager(context).activeNotifications.none { it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 })
    }

    @Test
    fun declineIsDeliveredNativelyToTheDeclineEndpoint() {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val request = AtomicReference<String>()
            val serving = Thread {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val head = StringBuilder()
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        head.append(line).append('\n')
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    val body = CharArray(length).also { input.read(it, 0, length) }
                    request.set(head.toString() + String(body))
                    socket.getOutputStream().write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }.apply { start() }
            call(
                "showIncoming",
                mapOf(
                    "callId" to WireValue.Text("dec-1"),
                    "name" to WireValue.Text("Ana"),
                    "timeoutMillis" to WireValue.Integer(45_000),
                    "declineUrl" to WireValue.Text("http://127.0.0.1:${server.localPort}/calls/dec-1/decline"),
                    "declineToken" to WireValue.Text("one-time"),
                ),
            )
            CallIntents.broadcast(context, "dec-1", CallIntents.ACTION_DECLINE).send()
            val action = nextAction()
            serving.join(5_000)
            assertEquals(2L, kind(action))
            assertTrue((action["serverNotified"] as WireValue.Flag).value)
            val sent = request.get()
            assertTrue(sent, sent.startsWith("POST /calls/dec-1/decline"))
            assertTrue(sent, sent.contains("Authorization: Bearer one-time"))
            assertEquals("dec-1", JSONObject(sent.substringAfter('{').let { "{$it" }).getString("call_id"))
        }
        // Without an endpoint the action is still queued, flagged as not delivered.
        incoming("dec-2")
        CallIntents.broadcast(context, "dec-2", CallIntents.ACTION_DECLINE).send()
        assertFalse((nextAction()["serverNotified"] as WireValue.Flag).value)
    }

    @Test
    fun incomingScreenKeepsActionsBelowTheCallerInLandscape() {
        incoming("land-1")
        val intent = Intent(context, IncomingCallActivity::class.java).putExtra(CallIntents.EXTRA_CALL_ID, "land-1")
        ActivityScenario.launch<IncomingCallActivity>(intent).use { scenario ->
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            Thread.sleep(2_000)
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                assertTrue(activity.resources.configuration.screenHeightDp < activity.resources.configuration.screenWidthDp)
                val header = decor.findViewWithTag<View>(IncomingCallActivity.TAG_HEADER)
                val accept = decor.findViewWithTag<View>(IncomingCallActivity.TAG_ACCEPT)
                val headerAt = IntArray(2).also(header::getLocationInWindow)
                val acceptAt = IntArray(2).also(accept::getLocationInWindow)
                assertTrue("header ${headerAt[1]}+${header.height} overlaps accept ${acceptAt[1]}", headerAt[1] + header.height <= acceptAt[1])
            }
        }
    }

    @Test
    fun pictureInPictureArmsEntersAndReportsTheHostMode() {
        ActivityScenario.launch(PipHostActivity::class.java).use { scenario ->
            Thread.sleep(500)
            val supported = (call("readiness").values["pictureInPictureSupported"] as WireValue.Flag).value
            val armed = call("pictureInPicture", mapOf("eligible" to WireValue.Flag(true), "width" to WireValue.Integer(9), "height" to WireValue.Integer(16)))
            assertTrue(armed.message, armed.ok)
            assertEquals(supported, (armed.values["available"] as WireValue.Flag).value)
            if (!supported) return
            val entered = call("enterPictureInPicture")
            assertTrue((entered.values["entered"] as WireValue.Flag).value)
            waitUntil(5_000) { CallPictureInPicture.isActive() }
            assertTrue((call("nextPictureInPicture").values["active"] as WireValue.Flag).value)
            scenario.onActivity { it.finish() }
            assertTrue(call("pictureInPicture", mapOf("eligible" to WireValue.Flag(false))).ok)
        }
    }

    @Test
    fun ongoingCallRunsAForegroundServiceAndHangUpStopsIt() {
        val since = System.currentTimeMillis() - 60_000
        val shown = call(
            "showOngoing",
            mapOf(
                "callId" to WireValue.Text("on-1"),
                "name" to WireValue.Text("Bruno"),
                "video" to WireValue.Flag(true),
                "sinceMillis" to WireValue.Integer(since),
            ),
        )
        assertTrue(shown.message, shown.ok)
        waitUntil(5_000) { notification("on-1") != null }
        val posted = notification("on-1")!!
        assertTrue(posted.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(since, posted.`when`)
        assertEquals("on-1", CallForegroundService.foregroundCallId)
        assertTrue(posted.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertEquals(Notification.CallStyle.CALL_TYPE_ONGOING, posted.extras.getInt(Notification.EXTRA_CALL_TYPE))
        }
        assertTrue(LockScreenPolicy.isActive(context))

        CallIntents.broadcast(context, "on-1", CallIntents.ACTION_HANG_UP).send()
        waitUntil(5_000) { notification("on-1") == null && CallForegroundService.foregroundCallId == null }
        assertEquals(4L, kind(nextAction()))
        assertFalse(LockScreenPolicy.isActive(context))
    }

    @Test
    fun pushMappingShowsIncomingCallWithoutPhpAndEndsIt() {
        val mapping = JSONObject()
            .put("typeField", "type").put("typeValues", JSONArray(listOf("2")))
            .put("idField", "call_id").put("nameField", "caller_name").put("avatarField", "caller_avatar")
            .put("videoField", "call_type").put("videoValues", JSONArray(listOf("2", "3")))
            .put("endedField", "call_event").put("endedValues", JSONArray(listOf("1")))
            .put("timeoutMillis", 30_000)
        assertTrue(call("configurePush", mapOf("mappingsJson" to WireValue.Text(JSONArray(listOf(mapping)).toString()))).ok)

        val ringing = JSONObject().put("type", 2).put("call_id", "push-1").put("caller_name", "Carla").put("call_type", "2")
        context.sendBroadcast(BackgroundPush.receivedIntent(context, "m1", dataJson = ringing.toString()))
        waitUntil(5_000) { notification("push-1") != null }
        val call = CallStore.get(context, "push-1")!!
        assertEquals("Carla", call.name)
        assertTrue(call.video)
        assertEquals(30_000L, call.timeoutMillis)

        val ignored = JSONObject().put("type", "9").put("call_id", "other")
        context.sendBroadcast(BackgroundPush.receivedIntent(context, "m2", dataJson = ignored.toString()))
        val ended = JSONObject(ringing.toString()).put("call_event", "1")
        context.sendBroadcast(BackgroundPush.receivedIntent(context, "m3", dataJson = ended.toString()))
        waitUntil(5_000) { notification("push-1") == null }
        assertNull(CallStore.get(context, "other"))
        assertEquals(0, CallStore.pendingActions(context))
    }

    @Test
    fun endedCallNeverRingsAgainFromPushOrPhp() {
        val mapping = JSONObject()
            .put("typeField", "type").put("typeValues", JSONArray(listOf("2")))
            .put("idField", "call_id").put("nameField", "caller_name")
            .put("endedField", "call_event").put("endedValues", JSONArray(listOf("1")))
        assertTrue(call("configurePush", mapOf("mappingsJson" to WireValue.Text(JSONArray(listOf(mapping)).toString()))).ok)
        val ringing = JSONObject().put("type", "2").put("call_id", "gone-1").put("caller_name", "Bia")
        context.sendBroadcast(BackgroundPush.receivedIntent(context, "e1", dataJson = ringing.toString()))
        waitUntil(5_000) { notification("gone-1") != null }
        context.sendBroadcast(BackgroundPush.receivedIntent(context, "e2", dataJson = JSONObject(ringing.toString()).put("call_event", "1").toString()))
        waitUntil(5_000) { notification("gone-1") == null }
        assertTrue(CallStore.isEnded(context, "gone-1"))

        // App reopened: the queued "received" push replayed to PHP shows it again.
        incoming("gone-1")
        // A late duplicate of the original push arrives too.
        context.sendBroadcast(BackgroundPush.receivedIntent(context, "e3", dataJson = ringing.toString()))
        Thread.sleep(1_000)
        assertNull(notification("gone-1"))
        assertNull(CallStore.get(context, "gone-1"))
        assertEquals(0, CallStore.pendingActions(context))

        // The tombstone expires, is bounded and does not block other calls.
        assertFalse(CallStore.isEnded(context, "gone-1", System.currentTimeMillis() + CallStore.ENDED_TTL_MILLIS))
        repeat(70) { CallStore.markEnded(context, "t-$it", 1_000L + it) }
        assertFalse(CallStore.isEnded(context, "t-0", 2_000L))
        assertTrue(CallStore.isEnded(context, "t-69", 2_000L))
        incoming("fresh-1")
        waitUntil(5_000) { notification("fresh-1") != null }
    }

    @Test
    fun matcherRejectsUnsafeIdsAndFallsBackToPushTitle() {
        val mappings = JSONArray().put(
            JSONObject().put("typeField", "kind").put("typeValues", JSONArray(listOf("call.incoming"))).put("idField", "id").put("nameField", "from"),
        )
        assertNull(PushCallMatcher.match(mappings, JSONObject().put("kind", "call.incoming").put("id", "../x")))
        val match = PushCallMatcher.match(mappings, JSONObject().put("kind", "call.incoming").put("id", "c-9"), "Title") as PushCallMatcher.Match.Incoming
        assertEquals("Title", match.call.name)
        assertFalse(match.call.video)
    }

    @Test
    fun actionsTakenWhilePhpIsSuspendedAreDeliveredInOrder() {
        CallController.record(context, CallController.KIND_OPEN, "q-1", null)
        CallController.record(context, CallController.KIND_DECLINE, "q-2", null)
        assertEquals(2, CallStore.pendingActions(context))
        assertEquals(3L, kind(nextAction()))
        assertEquals(2L, kind(nextAction()))
        assertEquals(0, CallStore.pendingActions(context))

        // A parked read is resolved directly by the next action.
        val latch = CountDownLatch(1)
        val delivered = AtomicReference<Map<String, WireValue>>()
        module.invoke("next", WireMap.encode(emptyMap()), ModuleCompletion { _, payload ->
            delivered.set(WireMap.decode(payload))
            latch.countDown()
        })
        CallController.record(context, CallController.KIND_HANG_UP, "q-3", null)
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertEquals(4L, kind(delivered.get()))
        assertEquals(0, CallStore.pendingActions(context))
    }
}
