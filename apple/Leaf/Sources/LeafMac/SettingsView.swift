import AppKit
import AuthenticationServices
import LeafCore
import Security
import SwiftUI

struct SettingsView: View {
    var body: some View {
        TabView {
            ConnectionPane().tabItem { Label("Notion", systemImage: "link") }
            AppearancePane().tabItem { Label("写作台", systemImage: "textformat") }
        }
        .frame(width: 520, height: 400)
    }
}

struct AppearancePane: View {
    @EnvironmentObject var desk: DeskSettings

    var body: some View {
        Form {
            Picker("字体", selection: $desk.font) { ForEach(DeskFont.allCases) { Text($0.label).tag($0) } }
            Slider(value: $desk.textSize, in: 13...24, step: 1) { Text("字号 \(Int(desk.textSize))") }
            Picker("专注", selection: $desk.focus) { ForEach(DeskSettings.Focus.allCases) { Text($0.label).tag($0) } }
            Toggle("打字机", isOn: $desk.typewriter)
            Toggle("文风检查", isOn: $desk.style)
            Toggle("显示来源（粘贴的字）", isOn: $desk.origins)
            Text("字体是 IBM Plex（OFL），中文跟随系统字体。来源标记只存在这台 Mac 上，不写进 Notion。")
                .font(.caption).foregroundStyle(.secondary)
        }
        .padding()
    }
}

/// Connect: sign in with Notion (through the shared OAuth relay) or paste an integration token, then
/// pick the database.
struct ConnectionPane: View {
    @EnvironmentObject var model: AppModel
    @State private var token = ""
    @State private var credential: NotionCredential?
    @State private var workspace = ""
    @State private var sources: [DataSourceInfo] = []
    @State private var picked: String?
    @State private var busy = false
    @State private var error: String?
    @State private var auth = WebAuth()

    var body: some View {
        Form {
            if let c = model.connection {
                LabeledContent("工作区", value: c.workspace)
                LabeledContent("数据库", value: c.databaseName)
                Button("断开连接") { model.disconnect() }
                Text("断开后这台 Mac 上的副本会删掉，Notion 里的不动。").font(.caption).foregroundStyle(.secondary)
            } else if credential != nil {
                Picker("用哪个数据库", selection: $picked) {
                    Text("请选择").tag(String?.none)
                    ForEach(sources) { Text($0.name).tag(Optional($0.id)) }
                }
                Button("连接") { Task { await connect() } }.disabled(picked == nil || busy)
                Text("Leaf 会在缺字段时补上「类型」「卡片颜色」「置顶」，并找到或新建「涂鸦墙」那一行。")
                    .font(.caption).foregroundStyle(.secondary)
            } else {
                Button("用 Notion 登录") { Task { await signIn() } }.disabled(busy)
                Divider()
                SecureField("或者粘贴内部集成 token（ntn_…）", text: $token)
                Button("用 token 连接") { Task { await use(.integration(token.trimmingCharacters(in: .whitespacesAndNewlines))) } }
                    .disabled(token.trimmingCharacters(in: .whitespaces).isEmpty || busy)
            }
            if busy { ProgressView().controlSize(.small) }
            if let error { Text(error).foregroundStyle(.orange) }
        }
        .padding()
    }

    private func signIn() async {
        busy = true
        defer { busy = false }
        error = nil
        let relay = NotionOAuthRelay()
        var bytes = [UInt8](repeating: 0, count: 24)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        let state = bytes.map { String(format: "%02x", $0) }.joined()
        do {
            let callback = try await auth.start(relay.startURL(state: state), scheme: NotionOAuthConfig.scheme)
            switch NotionOAuthRelay.code(from: callback, expectedState: state) {
            case .success(nil): return
            case .success(let code?):
                let credential = try await relay.exchange(code: code)
                await use(credential)
            case .failure: error = "Notion 没有完成授权，请再试一次"
            }
        } catch let e as NotionFailure {
            error = e.message
        } catch {
            // Closed the sign-in window: nothing to say.
        }
    }

    private func use(_ c: NotionCredential) async {
        busy = true
        defer { busy = false }
        error = nil
        do {
            let (ws, list) = try await SyncEngine.discover(model.notion, token: c.accessToken)
            workspace = ws
            sources = list
            credential = c
            picked = list.count == 1 ? list[0].id : nil
            if list.isEmpty { error = "这个连接还看不到数据库：在 Notion 里把数据库分享给它" }
        } catch let e as NotionFailure {
            error = e.message
        } catch {
            self.error = NotionFailure.unavailable.message
        }
    }

    private func connect() async {
        guard let c = credential, let id = picked, let source = sources.first(where: { $0.id == id }) else { return }
        busy = true
        defer { busy = false }
        do {
            try await model.connect(credential: c, workspace: workspace, picked: source)
            credential = nil
            token = ""
        } catch let e as NotionFailure {
            error = e.message
        } catch {
            self.error = NotionFailure.unavailable.message
        }
    }
}

/// ASWebAuthenticationSession catches the relay's `leaf://notion-oauth` redirect itself.
final class WebAuth: NSObject, ASWebAuthenticationPresentationContextProviding {
    private var session: ASWebAuthenticationSession?

    @MainActor
    func start(_ url: URL, scheme: String) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            let s = ASWebAuthenticationSession(url: url, callbackURLScheme: scheme) { callback, error in
                if let callback { continuation.resume(returning: callback) } else { continuation.resume(throwing: error ?? CancellationError()) }
            }
            s.presentationContextProvider = self
            s.prefersEphemeralWebBrowserSession = false
            session = s
            if !s.start() { continuation.resume(throwing: CancellationError()) }
        }
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated { NSApp.keyWindow ?? NSApp.windows.first ?? ASPresentationAnchor() }
    }
}
