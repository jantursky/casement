# Changelog

All notable changes to `io.github.jantursky:casement-*` are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - 2026-11-17

### Fixed

- `toolInfo.tool` always carries `name` and `inputSchema`, so the app SDK no longer rejects `ui/initialize` for a tool whose definition lacks them (found on a real Android device).
- The demo's bottom controls no longer sit under the Android navigation bar.

### Added

- `casement-core`: the host side of the MCP Apps bridge for spec 2026-01-26, JSON-RPC framing, tool visibility, host policy and CSP builder.
- `casement-compose`: `McpAppView` with renderers for Android, iOS, the web (Wasm) and the desktop (OpenJFX).
- `casement-mcp-sdk`: an adapter for the MCP Kotlin SDK and `connectMcpAppsHost`.
- A demo app for all four targets, with a hostile app that tries to break out.
