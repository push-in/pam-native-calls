import Foundation
import PamNative

/// iOS call surfaces need CallKit + PushKit (VoIP pushes); they are not shipped in 0.1.
/// Calls fail with a typed message, and `next` stays pending so listeners never spin.
public final class CallsModule: NativeModule, @unchecked Sendable {
    public init() {}

    public func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        switch method {
        case "next":
            return
        case "readiness":
            let values: [String: WireValue] = [
                "notificationsEnabled": .flag(false),
                "fullScreenIntentAllowed": .flag(false),
                "callStyleSupported": .flag(false),
            ]
            if let data = try? WireMap.encode(values) {
                completion(.success, data)
            } else {
                completion(.failure, Data("encoding failed".utf8))
            }
        default:
            completion(.failure, Data("pam-native-calls 0.1 supports Android only; CallKit support is planned.".utf8))
        }
    }
}
