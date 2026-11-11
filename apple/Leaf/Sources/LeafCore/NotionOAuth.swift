import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Notion sign-in goes through notion-oauth-relay (the Worker shared with Sheaf and the Android app),
/// which holds the client secret. The relay sends Notion's callback on to `leaf://notion-oauth`, the same
/// address Android uses, so no relay change is needed for the Mac; the code is then exchanged by a POST.
public enum NotionOAuthConfig {
    public static let relayBase = URL(string: "https://notion-oauth-relay.p2o51willam.workers.dev")!
    public static let app = "leaf"
    public static let scheme = "leaf"
    public static let host = "notion-oauth"
}

/// What the Keychain keeps: a pasted integration token, or an OAuth pair. Same text format as Android.
public enum NotionCredential: Equatable, Sendable {
    case integration(String)
    /// Each refresh rotates both tokens, so the pair is always written together.
    case oauth(access: String, refresh: String)

    public var accessToken: String {
        switch self {
        case .integration(let t): return t
        case .oauth(let a, _): return a
        }
    }

    public func encode() -> String {
        switch self {
        case .integration(let t): return t
        case .oauth(let a, let r):
            let data = try! JSONSerialization.data(withJSONObject: ["v": 1, "kind": "oauth", "access": a, "refresh": r], options: [.sortedKeys])
            return String(decoding: data, as: UTF8.self)
        }
    }

    public static func decode(_ stored: String) -> NotionCredential? {
        // An integration token never starts with a brace (ntn_… / secret_…).
        guard stored.hasPrefix("{") else { return stored.isEmpty ? nil : .integration(stored) }
        guard let json = (try? JSONSerialization.jsonObject(with: Data(stored.utf8))) as? JSON,
              json["kind"] as? String == "oauth",
              let a = json["access"] as? String, !a.isEmpty,
              let r = json["refresh"] as? String, !r.isEmpty else { return nil }
        return .oauth(access: a, refresh: r)
    }
}

public struct NotionOAuthRelay: Sendable {
    let base: URL
    let transport: NotionTransport

    public init(base: URL = NotionOAuthConfig.relayBase, transport: NotionTransport = URLSessionTransport()) {
        // Tokens only ever travel over https, except to a loopback test server.
        precondition(base.scheme == "https" || ["localhost", "127.0.0.1", "::1"].contains(base.host ?? ""))
        self.base = base
        self.transport = transport
    }

    /// The relay's start page for a sign-in carrying [state].
    public func startURL(state: String) -> URL {
        var c = URLComponents(url: base.appendingPathComponent(NotionOAuthConfig.app).appendingPathComponent("start"), resolvingAgainstBaseURL: false)!
        c.queryItems = [URLQueryItem(name: "state", value: state)]
        return c.url!
    }

    public func exchange(code: String) async throws -> NotionCredential {
        try await post("token", ["code": code])
    }

    public func refresh(_ refreshToken: String) async throws -> NotionCredential {
        try await post("refresh", ["refresh_token": refreshToken])
    }

    private func post(_ route: String, _ body: JSON) async throws -> NotionCredential {
        var request = URLRequest(url: base.appendingPathComponent(NotionOAuthConfig.app).appendingPathComponent(route))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let answer: (Int, Int?, Data)
        do {
            answer = try await transport.send(request)
        } catch {
            throw NotionFailure.unavailable
        }
        let (status, _, data) = answer
        // A throttle is not a bad credential: keep the pair and retry later.
        if status == 429 { throw NotionFailure.rateLimited }
        // Notion answers a spent or revoked code / refresh token with 400 invalid_grant: sign in again.
        if (400..<500).contains(status) { throw NotionFailure.unauthorized }
        guard (200..<300).contains(status) else { throw NotionFailure.unavailable }
        guard let json = (try? JSONSerialization.jsonObject(with: data)) as? JSON,
              let a = json["access_token"] as? String, !a.isEmpty,
              let r = json["refresh_token"] as? String, !r.isEmpty else { throw NotionFailure.invalidResponse }
        return .oauth(access: a, refresh: r)
    }

    /// Reads the relay's callback. Anything that is not ours, or whose state doesn't match, is refused.
    public static func code(from callback: URL, expectedState: String) -> Result<String?, NotionFailure> {
        guard callback.scheme == NotionOAuthConfig.scheme, callback.host == NotionOAuthConfig.host,
              let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems else { return .failure(.invalidResponse) }
        func value(_ name: String) -> String? { items.first { $0.name == name }?.value }
        guard let state = value("state"), constantTimeEquals(state, expectedState) else { return .failure(.unauthorized) }
        if value("error") == "access_denied" { return .success(nil) }
        guard value("error") == nil || value("error")!.isEmpty, let code = value("code"), !code.isEmpty else { return .failure(.unauthorized) }
        return .success(code)
    }

    static func constantTimeEquals(_ a: String, _ b: String) -> Bool {
        let x = Array(a.utf8), y = Array(b.utf8)
        guard x.count == y.count else { return false }
        return zip(x, y).reduce(0) { $0 | ($1.0 ^ $1.1) } == 0
    }
}
