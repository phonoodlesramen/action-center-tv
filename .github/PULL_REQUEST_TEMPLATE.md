## What and why

<!-- What this changes, and the issue it closes: "Closes #123". -->

## How it was checked

- [ ] `./gradlew lint testDebugUnitTest :guide-core:jvmTest :station:test assembleRelease`
- [ ] UI change: screenshots re-recorded (`recordRoborazziDebug`) and looked at
- [ ] Scripts change: `pwsh scripts/tests/run-tests.ps1`
- [ ] Tried on a device or the Android TV emulator (say which)

## Checklist

- [ ] Works with the D-pad alone, and focus lands where you'd expect
- [ ] No tokens, keys, real names or home IPs in code, tests, screenshots or logs
- [ ] A change to a TV over ADB goes through `Invoke-DeviceChange`, with its undo
- [ ] Docs updated if behaviour changed
- [ ] Parts written by an AI tool are said here
