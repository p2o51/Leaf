import AppKit

// Entry point. `--bench` runs the typing benchmark headless-ish (the window still has to exist so layout
// happens exactly as it would for a person) and exits with the numbers on stdout.
let app = NSApplication.shared
let delegate = SpikeAppDelegate(bench: CommandLine.arguments.contains("--bench"))
app.delegate = delegate
app.setActivationPolicy(.regular)
app.run()
