import LeafCore
import SwiftUI

/// 涂鸦墙: the newest lines first, and one field to add a line. Recolouring and photos stay on the phone
/// for now.
struct WallView: View {
    @EnvironmentObject var model: AppModel
    @State private var draft = ""
    @State private var color = NotionColor.default

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                TextField("写一句", text: $draft)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit(add)
                Menu {
                    ForEach(NotionColor.allCases, id: \.self) { c in Button(c.label) { color = c } }
                } label: {
                    Circle().fill(color == .default ? Color.secondary.opacity(0.3) : color.swatch).frame(width: 14, height: 14)
                }
                .menuStyle(.borderlessButton)
                .fixedSize()
            }
            .padding(10)
            List(model.scribbles) { s in
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Circle().fill(s.color == .default ? Color.clear : s.color.swatch).frame(width: 8, height: 8)
                    VStack(alignment: .leading, spacing: 2) {
                        if s.imageUrl != nil { Label("照片", systemImage: "photo").font(.caption).foregroundStyle(.secondary) }
                        if !s.text.isEmpty { Text(s.text).textSelection(.enabled) }
                    }
                }
                .padding(.vertical, 2)
            }
        }
    }

    private func add() {
        model.addScribble(draft, color: color)
        draft = ""
    }
}
