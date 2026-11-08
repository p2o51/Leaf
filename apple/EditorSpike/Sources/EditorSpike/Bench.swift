import AppKit
import QuartzCore

/// Types into the middle of the document one character at a time, the way a person would (through
/// insertText, so undo, selection change and focus mode all run), and forces the viewport to lay out and
/// draw after each key. Reports per-keystroke time; under ~8 ms at p95 is one 120 Hz frame.
enum Bench {
    static func run(on editor: EditorController) -> String {
        let phrase = Array("在长文中间连续打字，看排版跟不跟得上。Typing in the middle of a long draft. ")
        var report: [String] = []
        for mode in [FocusMode.off, .sentence, .paragraph] {
            editor.setFocus(mode)
            var samples: [Double] = []
            for i in 0..<2_000 {
                let started = CACurrentMediaTime()
                editor.textView.insertText(String(phrase[i % phrase.count]), replacementRange: editor.textView.selectedRange())
                editor.textView.textLayoutManager?.textViewportLayoutController.layoutViewport()
                editor.textView.displayIfNeeded()
                samples.append((CACurrentMediaTime() - started) * 1000)
            }
            samples.sort()
            func pct(_ p: Double) -> Double { samples[min(samples.count - 1, Int(Double(samples.count) * p))] }
            report.append(String(format: "focus=%@  p50=%.2fms  p95=%.2fms  p99=%.2fms  max=%.2fms", "\(mode)", pct(0.5), pct(0.95), pct(0.99), samples.last ?? 0))
        }
        let length = (editor.textView.string as NSString).length
        report.insert("EditorSpike bench · \(length) UTF-16 units · font \(editor.textView.font?.fontName ?? "?") · macOS \(ProcessInfo.processInfo.operatingSystemVersionString)", at: 0)
        return report.joined(separator: "\n")
    }
}
