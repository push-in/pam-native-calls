import CallKit
import PamNative
import XCTest
// Generated plugin target: PamPlugin<index>PushinbrPamNativeCalls (index = plugin order).
@testable import PamPlugin0PushinbrPamNativeCalls

/// XCTest mirror of CallsInstrumentedTest. Uncompiled — needs Mac validation;
/// CallKit flows need a device (the simulator does not ring).
final class CallsTests: XCTestCase {
    private let module = CallsModule()

    override func tearDown() {
        CallKitCenter.shared.endAll()
        CallStore.shared.clear()
        super.tearDown()
    }

    private func call(_ method: String, _ values: [String: WireValue] = [:], timeout: TimeInterval = 10) -> (ok: Bool, values: [String: WireValue]) {
        let done = expectation(description: method)
        var result: (Bool, [String: WireValue]) = (false, [:])
        module.invoke(method: method, payload: (try? WireMap.encode(values)) ?? Data()) { status, payload in
            result = (status == .success, (try? WireMap.decode(payload)) ?? [:])
            done.fulfill()
        }
        wait(for: [done], timeout: timeout)
        return result
    }

    func testIncomingCallIsStoredAndTimesOutWithAction() {
        XCTAssertTrue(call("showIncoming", [
            "callId": .text("c-1"), "name": .text("Ana"), "video": .flag(true), "timeoutMillis": .integer(5_000),
        ]).ok)
        XCTAssertEqual(CallStore.shared.get("c-1")?.video, true)
        let action = call("next", [:], timeout: 10)
        XCTAssertTrue(action.ok)
        XCTAssertEqual(action.values["kind"], .integer(CallKitCenter.kindTimeout))
        XCTAssertEqual(action.values["callId"], .text("c-1"))
        XCTAssertNil(CallStore.shared.get("c-1"))
    }

    func testDeclineAndHangUpRecordActionsThroughCallKitEnd() {
        XCTAssertTrue(call("showIncoming", ["callId": .text("c-2"), "name": .text("Bia")]).ok)
        let end = expectation(description: "end")
        CXCallController().request(CXTransaction(action: CXEndCallAction(call: CallInfo.uuid(for: "c-2")))) { _ in end.fulfill() }
        wait(for: [end], timeout: 5)
        XCTAssertEqual(call("next").values["kind"], .integer(CallKitCenter.kindDecline))

        XCTAssertTrue(call("showOngoing", ["callId": .text("c-3"), "name": .text("Caio")]).ok)
        XCTAssertEqual(CallStore.shared.get("c-3")?.ongoing, true)
        let hangUp = expectation(description: "hang up")
        CXCallController().request(CXTransaction(action: CXEndCallAction(call: CallInfo.uuid(for: "c-3")))) { _ in hangUp.fulfill() }
        wait(for: [hangUp], timeout: 5)
        XCTAssertEqual(call("next").values["kind"], .integer(CallKitCenter.kindHangUp))
    }

    func testEndRemovesSurfacesWithoutAction() {
        XCTAssertTrue(call("showIncoming", ["callId": .text("c-4"), "name": .text("Duda")]).ok)
        XCTAssertTrue(call("end", ["callId": .text("c-4")]).ok)
        XCTAssertNil(CallStore.shared.get("c-4"))
        XCTAssertEqual(CallStore.shared.pendingActions, 0)
        XCTAssertFalse(call("showIncoming", ["callId": .text("bad id!"), "name": .text("x")]).ok)
    }

    func testActionQueueSurvivesWithoutListener() {
        CallKitCenter.shared.record(CallKitCenter.kindOpen, callId: "q-1", call: nil)
        CallKitCenter.shared.record(CallKitCenter.kindAccept, callId: "q-2", call: nil)
        XCTAssertEqual(CallStore.shared.pendingActions, 2)
        XCTAssertEqual(call("next").values["callId"], .text("q-1"))
        XCTAssertEqual(call("next").values["callId"], .text("q-2"))
    }

    func testPushMappingMatchesIncomingAndEnded() throws {
        let mappings: [[String: Any]] = [[
            "typeField": "type", "typeValues": ["call.incoming"], "idField": "call_id", "nameField": "caller_name",
            "avatarField": "", "subtitleField": "", "deepLinkField": "link", "videoField": "call_type",
            "videoValues": ["video"], "endedField": "event", "endedValues": ["ended"], "timeoutMillis": 30_000,
            "acceptLabel": "", "declineLabel": "",
        ]]
        let incoming = PushCallMatcher.match(mappings, data: [
            "type": "call.incoming", "call_id": "c-9", "caller_name": "Eva", "call_type": "VIDEO", "link": "app://call/c-9",
        ])
        guard case let .incoming(info)? = incoming else { return XCTFail("incoming") }
        XCTAssertEqual(info.name, "Eva")
        XCTAssertTrue(info.video)
        XCTAssertEqual(info.timeoutMillis, 30_000)
        XCTAssertEqual(info.deepLink, "app://call/c-9")
        XCTAssertEqual(
            PushCallMatcher.match(mappings, data: ["type": "call.incoming", "call_id": "c-9", "event": "ended"]),
            .ended("c-9")
        )
        XCTAssertNil(PushCallMatcher.match(mappings, data: ["type": "other", "call_id": "c-9"]))
        XCTAssertNil(PushCallMatcher.match(mappings, data: ["type": "call.incoming", "call_id": "bad id!"]))
    }

    func testCallUuidIsDeterministicVersion5() {
        let uuid = CallInfo.uuid(for: "abc")
        XCTAssertEqual(uuid, CallInfo.uuid(for: "abc"))
        XCTAssertNotEqual(uuid, CallInfo.uuid(for: "abd"))
        XCTAssertEqual(uuid.uuidString[uuid.uuidString.index(uuid.uuidString.startIndex, offsetBy: 14)], "5")
    }

    func testReadinessReportsCallKitAvailability() {
        let readiness = call("readiness")
        XCTAssertTrue(readiness.ok)
        XCTAssertEqual(readiness.values["callStyleSupported"], .flag(CallKitCenter.callKitAllowed))
    }
}
