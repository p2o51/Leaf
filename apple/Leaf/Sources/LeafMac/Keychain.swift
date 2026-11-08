import Foundation
import LeafCore
import Security

/// The Notion credential lives in the login Keychain, never in the library folder or UserDefaults.
enum Keychain {
    static let service = "dev.chenwuyi.leaf.mac"
    static let account = "notion"

    static func read() -> NotionCredential? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return nil }
        return NotionCredential.decode(String(decoding: data, as: UTF8.self))
    }

    @discardableResult
    static func write(_ credential: NotionCredential) -> Bool {
        let data = Data(credential.encode().utf8)
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let update = SecItemUpdate(base as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if update == errSecSuccess { return true }
        var add = base
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    static func clear() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }
}

/// Tokens for sync: the Keychain's, refreshed through the relay when Notion turns an OAuth token down.
actor KeychainCredentials: Credentials {
    let relay = NotionOAuthRelay()

    func token() async -> String? { Keychain.read()?.accessToken }

    func refreshed(stale: String) async -> String? {
        guard let current = Keychain.read() else { return nil }
        if current.accessToken != stale { return current.accessToken }
        guard case .oauth(_, let refresh) = current else { return nil }
        guard let fresh = try? await relay.refresh(refresh) else { return nil }
        // Disconnected or signed in again meanwhile: the new pair belongs to nothing current.
        guard Keychain.read() == current else { return nil }
        Keychain.write(fresh)
        return fresh.accessToken
    }
}
