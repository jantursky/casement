# Contributing

Thanks for helping. Bug reports, docs fixes and pull requests are all welcome.

## Before you start

- For anything bigger than a small fix, open an issue first so we can agree on the approach.
- Sandbox behavior depends on the web view of the platform. Say which OS version and device or emulator you tested on, and attach the app document if a bug depends on it.

## Setup

You need JDK 17 and an Android SDK. The Gradle wrapper handles the rest. iOS needs Xcode. The web tests use [Bun](https://bun.sh) and Node.js (versions in `.bun-version` and `.node-version`).

```sh
./gradlew :casement-core:jvmTest                      # quickest feedback
scripts/build-fixtures.sh                             # real example apps for the conformance tests
./gradlew :samples:demo:shared:run                    # the demo on the desktop
```

## Where things live

| path | What |
| --- | --- |
| `casement-core` | Bridge, JSON-RPC, tool visibility, policy and CSP. No Compose. |
| `casement-compose` | `McpAppView` and the renderers: `androidMain`, `iosMain`, `wasmJsMain`, `jvmMain`. |
| `casement-mcp-sdk` | The adapter for the MCP Kotlin SDK. |
| `web-sandbox` | The sandbox page the web renderer needs, served from another origin. |
| `fixtures` | The hostile app and the recorded answers of the real ext-apps apps. |
| `samples/demo` | The demo for Android, iOS, web and the desktop, with its tests. |

## Checks

```sh
./gradlew spotlessCheck check koverVerify apiCheck    # what CI runs
./gradlew spotlessApply                               # format (ktlint)
./gradlew apiDump                                     # after changing the public API
```

Run `spotlessApply` on its own, not in the same invocation as a build: it reads build output folders.

## Conventions

- Kotlin in the official style, explicit API mode, everything that is not public API is `internal`.
- Changing the public API means running `apiDump` and reviewing the diff in the `api` folders.
- Every behavior change comes with a test. Anything that touches the sandbox needs a test with the hostile app in `fixtures/hostile` on the platform you changed.
- Do not claim a platform works unless you ran it. The README table says where each suite ran.
- Add a line under `## [Unreleased]` in `CHANGELOG.md` for anything users will notice.

## License

By contributing you agree that your contributions are licensed under the MIT license.
