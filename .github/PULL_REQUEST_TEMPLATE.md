## What changed

<!-- One or two sentences. What a reviewer needs to know before reading the diff. -->

## Why

<!-- The problem this solves. If it is a fix, what the failure looked like. -->

## How it was verified

<!-- What you actually ran, and what it printed. "Tests pass" is not a result;
     "20 passed" is. If you changed a gate, say how you confirmed it can fail. -->

- [ ] `pnpm --filter ./apps/web test`
- [ ] `pnpm --filter ./apps/web run lint`
- [ ] `cd services/feedback && go vet ./... && go test ./...` (if Go changed)
- [ ] Android unit tests, if Android changed: `cd apps/android && ./gradlew testDebugUnitTest`
      (needs `JAVA_HOME` on Android Studio's JBR; see apps/android/README.md).
      CI does not run them, so this is the only gate on that suite.
- [ ] iOS `MonOcrCore` tests, if iOS changed: `cd apps/ios && sh Scripts/swift-test.sh`.
      The `ios-core` job runs these on every push, so this is a faster copy of a
      gate that does exist. The app target is still not built anywhere.

## Claims

<!-- Delete if none. Any number added to a README, model card or UI string names
     what it measures and where it can be traced to. A figure with no source is
     the failure mode this repo has a CI job for. -->
