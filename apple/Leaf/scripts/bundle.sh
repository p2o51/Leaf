#!/bin/bash
# Builds Leaf.app from the Swift package: release build, Info.plist, bundled IBM Plex fonts, ad-hoc
# signature. Output: apple/Leaf/build/Leaf.app. No Xcode project and no Apple Developer account needed.
set -euo pipefail
cd "$(dirname "$0")/.."
swift build -c release --product LeafMac
BIN="$(swift build -c release --show-bin-path)/LeafMac"
APP=build/Leaf.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/LeafMac"
cp Resources/Info.plist "$APP/Contents/Info.plist"
cp -R Resources/Fonts "$APP/Contents/Resources/Fonts"
codesign --force --sign - "$APP"
echo "$APP"
