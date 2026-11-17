# Changelog

All notable changes to `io.github.jantursky:casement-*` are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `casement-core`: the host side of the MCP Apps bridge for spec 2026-01-26, JSON-RPC framing, tool visibility, host policy and CSP builder.
- `casement-compose`: `McpAppView` with renderers for Android, iOS, the web (Wasm) and the desktop (OpenJFX).
- `casement-mcp-sdk`: an adapter for the MCP Kotlin SDK and `connectMcpAppsHost`.
- A demo app for all four targets, with a hostile app that tries to break out.
