import AVFoundation
import CallKit
import CryptoKit
import Foundation
import PamNative
import PushKit
import UIKit
import UserNotifications

/// PAM module `calls` on iOS: CallKit surfaces, PushKit VoIP ringing and the
/// same durable action queue as Android.
public final class CallsModule: NativeModule, @unchecked Sendable {
    public init() {
        // Modules are created while the app launches, which is when PushKit
        // must be registered so VoIP pushes wake the process.
        DispatchQueue.main.async { CallKitCenter.shared.start() }
    }

    public func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        do {
            let values = try WireMap.decode(payload)
            let center = CallKitCenter.shared
            switch method {
            case "showIncoming":
                let call = try CallInfo.incoming(values)
                DispatchQueue.main.async {
                    center.showIncoming(call) { error in
                        error == nil ? succeed(completion) : fail(completion, error!.localizedDescription)
                    }
                }
            case "showOngoing":
                let id = try CallInfo.validId(values.text("callId"))
                DispatchQueue.main.async {
                    center.showOngoing(CallInfo.ongoing(values, id: id, known: CallStore.shared.get(id))) { error in
                        error == nil ? succeed(completion) : fail(completion, error!.localizedDescription)
                    }
                }
            case "end":
                let id = try CallInfo.validId(values.text("callId"))
                DispatchQueue.main.async {
                    center.end(id)
                    succeed(completion)
                }
            case "endAll":
                DispatchQueue.main.async {
                    center.endAll()
                    succeed(completion)
                }
            case "next":
                center.next(completion)
            case "configurePush":
                try CallStore.shared.setMappings(values.text("mappingsJson"))
                succeed(completion)
            case "voipToken":
                center.voipToken(completion)
            case "readiness":
                UNUserNotificationCenter.current().getNotificationSettings { settings in
                    succeed(completion, [
                        "notificationsEnabled": .flag(settings.authorizationStatus == .authorized ||
                            settings.authorizationStatus == .provisional),
                        // CallKit always presents full screen on the lock screen.
                        "fullScreenIntentAllowed": .flag(CallKitCenter.callKitAllowed),
                        "callStyleSupported": .flag(CallKitCenter.callKitAllowed),
                    ])
                }
            case "openFullScreenSettings":
                DispatchQueue.main.async {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                    succeed(completion)
                }
            default:
                throw CallsError("Unknown calls method \(method)")
            }
        } catch {
            fail(completion, error.localizedDescription)
        }
    }
}

struct CallsError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

func succeed(_ completion: ModuleCompletion, _ values: [String: WireValue] = [:]) {
    completion(.success, (try? WireMap.encode(values)) ?? Data())
}

func fail(_ completion: ModuleCompletion, _ message: String) {
    completion(.failure, Data(message.utf8))
}

extension Dictionary where Key == String, Value == WireValue {
    func text(_ key: String) throws -> String {
        guard case let .text(value)? = self[key] else { throw CallsError("Missing \(key)") }
        return value
    }

    func text(_ key: String, _ fallback: String) -> String {
        if case let .text(value)? = self[key] { return value }
        return fallback
    }

    func integer(_ key: String, _ fallback: Int64) -> Int64 {
        if case let .integer(value)? = self[key] { return value }
        return fallback
    }

    func flag(_ key: String, _ fallback: Bool = false) -> Bool {
        if case let .flag(value)? = self[key] { return value }
        return fallback
    }
}

/// A call known to the plugin (ringing or ongoing); persisted like Android.
struct CallInfo: Codable, Equatable {
    var id: String
    var name: String
    var avatar = ""
    var video = false
    var subtitle = ""
    var deepLink = ""
    var dataJson = "{}"
    var timeoutMillis: Int64 = 45_000
    var acceptLabel = ""
    var declineLabel = ""
    var hangUpLabel = ""
    var sinceMillis: Int64 = CallInfo.now()
    var ongoing = false
    var ringingSince: Int64 = CallInfo.now()

    static func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1_000) }

    static func validId(_ id: String) throws -> String {
        let trimmed = id.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.range(of: "^[A-Za-z0-9_.:-]{1,128}$", options: .regularExpression) != nil else {
            throw CallsError("Invalid call id")
        }
        return trimmed
    }

    static func incoming(_ values: [String: WireValue]) throws -> CallInfo {
        CallInfo(
            id: try validId(values.text("callId")),
            name: String(try values.text("name").prefix(256)),
            avatar: String(values.text("avatar", "").prefix(2_048)),
            video: values.flag("video"),
            subtitle: String(values.text("subtitle", "").prefix(256)),
            deepLink: String(values.text("deepLink", "").prefix(2_048)),
            dataJson: values.text("dataJson", "{}"),
            timeoutMillis: min(max(values.integer("timeoutMillis", 45_000), 5_000), 300_000),
            acceptLabel: String(values.text("acceptLabel", "").prefix(64)),
            declineLabel: String(values.text("declineLabel", "").prefix(64))
        )
    }

    static func ongoing(_ values: [String: WireValue], id: String, known: CallInfo?) -> CallInfo {
        func pick(_ key: String, _ fallback: String?) -> String {
            let value = values.text(key, "")
            return value.trimmingCharacters(in: .whitespaces).isEmpty ? (fallback ?? "") : value
        }
        return CallInfo(
            id: id,
            name: String(pick("name", known?.name).prefix(256)),
            avatar: String(pick("avatar", known?.avatar).prefix(2_048)),
            video: values.flag("video", known?.video ?? false),
            subtitle: String(values.text("subtitle", "").prefix(256)),
            deepLink: String(pick("deepLink", known?.deepLink).prefix(2_048)),
            dataJson: known?.dataJson ?? "{}",
            hangUpLabel: String(values.text("hangUpLabel", "").prefix(64)),
            sinceMillis: values.integer("sinceMillis", now()),
            ongoing: true
        )
    }

    /// Deterministic CallKit UUID for a PAM call id (SHA-256, RFC 4122 v5 bits).
    static func uuid(for id: String) -> UUID {
        var bytes = Array(SHA256.hash(data: Data("pam-call:\(id)".utf8)).prefix(16))
        bytes[6] = (bytes[6] & 0x0F) | 0x50
        bytes[8] = (bytes[8] & 0x3F) | 0x80
        return UUID(uuid: (
            bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
            bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]
        ))
    }
}

/// UserDefaults-backed state: calls, queued actions and push mappings.
final class CallStore: @unchecked Sendable {
    static let shared = CallStore()
    static let maxActions = 64
    private let lock = NSLock()
    private let defaults: UserDefaults
    private let callsKey = "dev.pam.calls.calls"
    private let actionsKey = "dev.pam.calls.actions"
    private let mappingsKey = "dev.pam.calls.mappings"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func put(_ call: CallInfo) {
        lock.lock()
        defer { lock.unlock() }
        var calls = load()
        calls[call.id] = call
        save(calls)
    }

    func get(_ id: String) -> CallInfo? {
        lock.lock()
        defer { lock.unlock() }
        return load()[id]
    }

    @discardableResult
    func remove(_ id: String) -> CallInfo? {
        lock.lock()
        defer { lock.unlock() }
        var calls = load()
        let removed = calls.removeValue(forKey: id)
        save(calls)
        return removed
    }

    func all() -> [CallInfo] {
        lock.lock()
        defer { lock.unlock() }
        return Array(load().values)
    }

    func call(for uuid: UUID) -> CallInfo? {
        all().first { CallInfo.uuid(for: $0.id) == uuid }
    }

    func pushAction(_ action: [String: Any]) {
        lock.lock()
        defer { lock.unlock() }
        var actions = defaults.array(forKey: actionsKey) as? [[String: Any]] ?? []
        actions.append(action)
        if actions.count > Self.maxActions { actions.removeFirst(actions.count - Self.maxActions) }
        defaults.set(actions, forKey: actionsKey)
    }

    func popAction() -> [String: Any]? {
        lock.lock()
        defer { lock.unlock() }
        var actions = defaults.array(forKey: actionsKey) as? [[String: Any]] ?? []
        guard !actions.isEmpty else { return nil }
        let first = actions.removeFirst()
        defaults.set(actions, forKey: actionsKey)
        return first
    }

    var pendingActions: Int {
        (defaults.array(forKey: actionsKey) as? [[String: Any]])?.count ?? 0
    }

    func setMappings(_ json: String) throws {
        guard (try? JSONSerialization.jsonObject(with: Data(json.utf8))) is [Any] else {
            throw CallsError("Push mappings must be a JSON array")
        }
        defaults.set(json, forKey: mappingsKey)
    }

    func mappings() -> [[String: Any]] {
        let json = defaults.string(forKey: mappingsKey) ?? "[]"
        return (try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [[String: Any]]) ?? []
    }

    func clear() {
        lock.lock()
        defer { lock.unlock() }
        [callsKey, actionsKey, mappingsKey].forEach(defaults.removeObject(forKey:))
    }

    private func load() -> [String: CallInfo] {
        guard let data = defaults.data(forKey: callsKey) else { return [:] }
        return (try? JSONDecoder().decode([String: CallInfo].self, from: data)) ?? [:]
    }

    private func save(_ calls: [String: CallInfo]) {
        defaults.set(try? JSONEncoder().encode(calls), forKey: callsKey)
    }
}

/// Pure evaluation of persisted push mappings (Android PushCallMatcher).
enum PushCallMatcher {
    enum Match: Equatable {
        case incoming(CallInfo)
        case ended(String)
    }

    static func match(_ mappings: [[String: Any]], data: [String: Any], fallbackTitle: String = "") -> Match? {
        func text(_ key: String) -> String? {
            guard !key.isEmpty, let value = data[key], !(value is NSNull) else { return nil }
            if let string = value as? String { return string }
            if let number = value as? NSNumber {
                return CFGetTypeID(number) == CFBooleanGetTypeID() ? (number.boolValue ? "true" : "false") : number.stringValue
            }
            return "\(value)"
        }
        for mapping in mappings {
            let strings = { (key: String) in (mapping[key] as? [Any])?.map { "\($0)" } ?? [] }
            guard let type = text(mapping["typeField"] as? String ?? "type"), strings("typeValues").contains(type),
                  let rawId = text(mapping["idField"] as? String ?? "call_id"),
                  let callId = try? CallInfo.validId(rawId) else { continue }
            let endedField = mapping["endedField"] as? String ?? ""
            if !endedField.isEmpty, let ended = text(endedField), strings("endedValues").contains(ended) {
                return .ended(callId)
            }
            let videoField = mapping["videoField"] as? String ?? ""
            let video = !videoField.isEmpty &&
                strings("videoValues").map { $0.lowercased() }.contains(text(videoField)?.lowercased() ?? "\u{0}")
            let field = { (key: String) in text(mapping[key] as? String ?? "") ?? "" }
            let name = field("nameField")
            let dataJson = (try? JSONSerialization.data(withJSONObject: data)).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
            return .incoming(CallInfo(
                id: callId,
                name: String((name.trimmingCharacters(in: .whitespaces).isEmpty ? fallbackTitle : name).prefix(256)),
                avatar: String(field("avatarField").prefix(2_048)),
                video: video,
                subtitle: String(field("subtitleField").prefix(256)),
                deepLink: String(field("deepLinkField").prefix(2_048)),
                dataJson: dataJson,
                timeoutMillis: min(max((mapping["timeoutMillis"] as? NSNumber)?.int64Value ?? 45_000, 5_000), 300_000),
                acceptLabel: mapping["acceptLabel"] as? String ?? "",
                declineLabel: mapping["declineLabel"] as? String ?? ""
            ))
        }
        return nil
    }
}

/// CallKit provider, PushKit registry and the action channel.
final class CallKitCenter: NSObject, CXProviderDelegate, PKPushRegistryDelegate, @unchecked Sendable {
    static let shared = CallKitCenter()
    static let kindAccept: Int64 = 1
    static let kindDecline: Int64 = 2
    static let kindOpen: Int64 = 3
    static let kindHangUp: Int64 = 4
    static let kindTimeout: Int64 = 5

    /// CallKit is not allowed for apps distributed in mainland China.
    static var callKitAllowed: Bool {
        if #available(iOS 16, *) {
            return Locale.current.region?.identifier != "CN"
        }
        return Locale.current.regionCode != "CN"
    }

    private let store = CallStore.shared
    private let controller = CXCallController()
    private lazy var provider: CXProvider = {
        let configuration = CXProviderConfiguration()
        configuration.supportsVideo = true
        configuration.maximumCallGroups = 1
        configuration.maximumCallsPerCallGroup = 1
        configuration.supportedHandleTypes = [.generic]
        configuration.includesCallsInRecents = false
        if let icon = UIImage(named: "CallKitIcon") {
            configuration.iconTemplateImageData = icon.pngData()
        }
        let provider = CXProvider(configuration: configuration)
        provider.setDelegate(self, queue: nil)
        return provider
    }()
    private var registry: PKPushRegistry?
    private var timeouts: [String: DispatchWorkItem] = [:]
    private let lock = NSLock()
    private var waiter: ModuleCompletion?
    private var tokenWaiters: [ModuleCompletion] = []
    private var token: String? = UserDefaults.standard.string(forKey: "dev.pam.calls.voipToken")

    func start() {
        _ = provider
        guard registry == nil else { return }
        let registry = PKPushRegistry(queue: .main)
        registry.delegate = self
        registry.desiredPushTypes = [.voIP]
        self.registry = registry
    }

    // MARK: Surfaces

    func showIncoming(_ call: CallInfo, completion: ((Error?) -> Void)? = nil) {
        if store.get(call.id)?.ongoing == true {
            completion?(nil)
            return
        }
        store.put(call)
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: call.name.isEmpty ? call.id : call.name)
        update.localizedCallerName = call.name
        update.hasVideo = call.video
        update.supportsHolding = false
        update.supportsGrouping = false
        update.supportsUngrouping = false
        update.supportsDTMF = false
        provider.reportNewIncomingCall(with: CallInfo.uuid(for: call.id), update: update) { error in
            DispatchQueue.main.async {
                if error != nil {
                    self.store.remove(call.id)
                } else {
                    self.scheduleTimeout(call)
                }
                completion?(error)
            }
        }
    }

    func showOngoing(_ call: CallInfo, completion: ((Error?) -> Void)? = nil) {
        cancelTimeout(call.id)
        let known = store.get(call.id)
        store.put(call)
        let uuid = CallInfo.uuid(for: call.id)
        if known != nil, controller.callObserver.calls.contains(where: { $0.uuid == uuid }) {
            // Answered from CallKit (or already reported): nothing else to show.
            provider.reportOutgoingCall(with: uuid, connectedAt: Date(timeIntervalSince1970: Double(call.sinceMillis) / 1_000))
            completion?(nil)
            return
        }
        // Outgoing call started in the app: register it with CallKit.
        let handle = CXHandle(type: .generic, value: call.name.isEmpty ? call.id : call.name)
        let start = CXStartCallAction(call: uuid, handle: handle)
        start.isVideo = call.video
        start.contactIdentifier = call.name
        controller.request(CXTransaction(action: start)) { error in
            DispatchQueue.main.async {
                if error == nil {
                    let update = CXCallUpdate()
                    update.remoteHandle = handle
                    update.localizedCallerName = call.name
                    update.hasVideo = call.video
                    self.provider.reportCall(with: uuid, updated: update)
                    self.provider.reportOutgoingCall(
                        with: uuid,
                        connectedAt: Date(timeIntervalSince1970: Double(call.sinceMillis) / 1_000)
                    )
                }
                completion?(error)
            }
        }
    }

    /// Removes every surface without emitting an action (Android `end`).
    func end(_ callId: String) {
        cancelTimeout(callId)
        let uuid = CallInfo.uuid(for: callId)
        store.remove(callId)
        if controller.callObserver.calls.contains(where: { $0.uuid == uuid && !$0.hasEnded }) {
            provider.reportCall(with: uuid, endedAt: Date(), reason: .remoteEnded)
        }
    }

    func endAll() {
        store.all().forEach { end($0.id) }
        for call in controller.callObserver.calls where !call.hasEnded {
            provider.reportCall(with: call.uuid, endedAt: Date(), reason: .remoteEnded)
        }
    }

    // MARK: Actions

    func next(_ completion: @escaping ModuleCompletion) {
        lock.lock()
        if let action = store.popAction() {
            lock.unlock()
            succeed(completion, Self.wire(action))
            return
        }
        let replaced = waiter
        waiter = completion
        lock.unlock()
        if let replaced { fail(replaced, "Action read replaced") }
    }

    func record(_ kind: Int64, callId: String, call: CallInfo?) {
        let action: [String: Any] = [
            "kind": kind,
            "callId": callId,
            "video": call?.video ?? false,
            "deepLink": call?.deepLink ?? "",
            "dataJson": call?.dataJson ?? "{}",
            "at": CallInfo.now(),
        ]
        lock.lock()
        let receiver = waiter
        if receiver == nil { store.pushAction(action) } else { waiter = nil }
        lock.unlock()
        if let receiver { succeed(receiver, Self.wire(action)) }
    }

    static func wire(_ action: [String: Any]) -> [String: WireValue] {
        [
            "kind": .integer((action["kind"] as? NSNumber)?.int64Value ?? 0),
            "callId": .text(action["callId"] as? String ?? ""),
            "video": .flag((action["video"] as? NSNumber)?.boolValue ?? false),
            "deepLink": .text(action["deepLink"] as? String ?? ""),
            "dataJson": .text(action["dataJson"] as? String ?? "{}"),
            "at": .integer((action["at"] as? NSNumber)?.int64Value ?? 0),
        ]
    }

    private func scheduleTimeout(_ call: CallInfo) {
        cancelTimeout(call.id)
        let work = DispatchWorkItem { [weak self] in
            guard let self, let current = self.store.get(call.id), !current.ongoing else { return }
            self.timeouts[call.id] = nil
            self.provider.reportCall(with: CallInfo.uuid(for: call.id), endedAt: Date(), reason: .unanswered)
            self.store.remove(call.id)
            self.record(Self.kindTimeout, callId: call.id, call: current)
        }
        timeouts[call.id] = work
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(call.timeoutMillis)), execute: work)
    }

    private func cancelTimeout(_ callId: String) {
        timeouts.removeValue(forKey: callId)?.cancel()
    }

    // MARK: CXProviderDelegate

    func providerDidReset(_ provider: CXProvider) {
        store.all().forEach { cancelTimeout($0.id); store.remove($0.id) }
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        guard let call = store.call(for: action.callUUID) else {
            action.fail()
            return
        }
        cancelTimeout(call.id)
        record(Self.kindAccept, callId: call.id, call: call)
        if !call.deepLink.isEmpty, let url = URL(string: call.deepLink) {
            DispatchQueue.main.async { UIApplication.shared.open(url) }
        }
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        guard let call = store.call(for: action.callUUID) else {
            action.fulfill()
            return
        }
        cancelTimeout(call.id)
        store.remove(call.id)
        record(call.ongoing ? Self.kindHangUp : Self.kindDecline, callId: call.id, call: call)
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        action.fulfill()
    }

    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        NotificationCenter.default.post(name: .pamCallAudioActivated, object: audioSession)
    }

    func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        NotificationCenter.default.post(name: .pamCallAudioDeactivated, object: audioSession)
    }

    // MARK: PushKit

    func voipToken(_ completion: @escaping ModuleCompletion) {
        lock.lock()
        if let token {
            lock.unlock()
            succeed(completion, ["token": .text(token), "provider": .integer(3)])
            return
        }
        tokenWaiters.append(completion)
        lock.unlock()
        DispatchQueue.main.async { self.start() }
    }

    func pushRegistry(_ registry: PKPushRegistry, didUpdate pushCredentials: PKPushCredentials, for type: PKPushType) {
        let hex = pushCredentials.token.map { String(format: "%02x", $0) }.joined()
        UserDefaults.standard.set(hex, forKey: "dev.pam.calls.voipToken")
        lock.lock()
        token = hex
        let waiting = tokenWaiters
        tokenWaiters.removeAll()
        lock.unlock()
        waiting.forEach { succeed($0, ["token": .text(hex), "provider": .integer(3)]) }
    }

    func pushRegistry(_ registry: PKPushRegistry, didInvalidatePushTokenFor type: PKPushType) {
        UserDefaults.standard.removeObject(forKey: "dev.pam.calls.voipToken")
        lock.lock()
        token = nil
        lock.unlock()
    }

    /// iOS 13+ terminates apps that receive a VoIP push without reporting a
    /// call, so unmatched and "ended" pushes report a call that ends at once.
    func pushRegistry(
        _ registry: PKPushRegistry,
        didReceiveIncomingPushWith payload: PKPushPayload,
        for type: PKPushType,
        completion: @escaping () -> Void
    ) {
        var data: [String: Any] = [:]
        for (key, value) in payload.dictionaryPayload {
            if let key = key as? String, key != "aps" { data[key] = value }
        }
        let aps = payload.dictionaryPayload["aps"] as? [String: Any]
        let title = ((aps?["alert"] as? [String: Any])?["title"] as? String) ?? ""
        switch PushCallMatcher.match(store.mappings(), data: data, fallbackTitle: title) {
        case let .incoming(call)?:
            showIncoming(call) { _ in completion() }
        case let .ended(callId)?:
            if store.get(callId) != nil {
                end(callId)
                completion()
            } else {
                reportAndEnd(id: callId, completion: completion)
            }
        case nil:
            reportAndEnd(id: "unmatched-\(UUID().uuidString)", completion: completion)
        }
    }

    private func reportAndEnd(id: String, completion: @escaping () -> Void) {
        let uuid = CallInfo.uuid(for: id)
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: "")
        provider.reportNewIncomingCall(with: uuid, update: update) { _ in
            self.provider.reportCall(with: uuid, endedAt: Date(), reason: .remoteEnded)
            completion()
        }
    }
}

public extension Notification.Name {
    /// CallKit activated the call audio session (start WebRTC audio here).
    static let pamCallAudioActivated = Notification.Name("dev.pam.calls.audioActivated")
    /// CallKit deactivated the call audio session.
    static let pamCallAudioDeactivated = Notification.Name("dev.pam.calls.audioDeactivated")
}
