/// A long mixed Chinese/English draft with short and long paragraphs, so wrapping, CJK fallback and
/// paragraph spacing are all exercised.
enum SampleText {
    static func make(characters: Int) -> String {
        let paragraphs = [
            "写作的时候，界面应该退到后面。光标所在的这一句是亮的，其余的字淡下去，眼睛就只剩下一件事可做。",
            "A good editor disappears. You notice it only when it stutters, when the caret lags a frame behind your fingers, or when a long draft makes every keystroke a little heavier than the last.",
            "随笔是零碎的，文稿是完整的。文稿要能一口气写上几千字，中间来回改，不卡、不跳、不丢。",
            "Focus mode, typewriter scrolling and careful type are small things. Together they decide whether you keep writing.",
            "所以这里先只做一个窗口、一个文本框：五万字的长文，在中间连续打字，看看系统自带的 TextKit 2 能不能一直跟得上。如果跟得上，Leaf 的 Mac 版就建在它上面；如果跟不上，就要在动手之前换路线。",
        ]
        var out = ""
        var i = 0
        while out.count < characters {
            out += paragraphs[i % paragraphs.count] + "\n"
            i += 1
        }
        return out
    }
}
