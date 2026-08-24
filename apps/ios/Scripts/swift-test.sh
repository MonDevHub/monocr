#!/bin/sh
# Run the MonOcrCore test suite.
#
# Two things this wraps, both of which have bitten this app already.
#
# 1. Testing.framework. Apple's Command Line Tools put swift-testing in a
#    Developer framework directory that SwiftPM does not search, so `swift test`
#    fails with "no such module 'Testing'". The fix is three flags on the command
#    line, where they reach SwiftPM's generated entry-point module too. Putting
#    them in Package.swift instead reaches only the test target, leaves the entry
#    point compiled out by its `#if canImport(Testing)` guard, and produces a run
#    that exits 0 having executed nothing.
#
# 2. That silent zero-test pass. `xcodebuild test` reported success against a
#    target with no tests in it for months. This script therefore refuses to exit
#    0 unless the run actually reported a test count.

set -eu

cd "$(dirname "$0")/.."

FLAGS=""
DEVELOPER="$(xcode-select -p)"
for CANDIDATE in \
    "$DEVELOPER/Library/Developer/Frameworks" \
    "$DEVELOPER/Platforms/MacOSX.platform/Developer/Library/Frameworks"; do
    [ -d "$CANDIDATE/Testing.framework" ] || continue
    # lib_TestingInterop is loaded by @rpath from a sibling of the framework dir.
    INTEROP="$(dirname "$CANDIDATE")/usr/lib"
    FLAGS="-Xswiftc -F -Xswiftc $CANDIDATE -Xlinker -F -Xlinker $CANDIDATE -Xlinker -rpath -Xlinker $CANDIDATE -Xlinker -rpath -Xlinker $INTEROP"
    break
done

LOG="MonOcrCore/.build/swift-test.log"
mkdir -p "$(dirname "$LOG")"

# --disable-xctest: XCTest.framework is absent from the Command Line Tools, and
# asking for it fails the run before swift-testing gets a chance.
# Status captured rather than piped, so a real test failure is not masked by tee.
STATUS=0
# shellcheck disable=SC2086
swift test --package-path MonOcrCore --disable-xctest $FLAGS >"$LOG" 2>&1 || STATUS=$?
cat "$LOG"

if ! grep -q "Test run with" "$LOG"; then
    echo "swift-test: the run reported no test count, so it ran no tests. Not calling that a pass." >&2
    exit 1
fi

exit "$STATUS"
