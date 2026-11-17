# ext-apps fixtures

Built, unmodified single-file apps from [modelcontextprotocol/ext-apps](https://github.com/modelcontextprotocol/ext-apps)
at version 2.1.0 (commit `ea3e9d0e`). Tests run them through the bridge to check Casement against real apps.

| File | Source example | What it covers |
| --- | --- | --- |
| `get-time.html` | `examples/basic-server-vanillajs` | Handshake, tool result, app-initiated tool call, `ui/message`, logging, `ui/open-link`, theming |

Rebuild with `scripts/build-fixtures.sh`. The examples are licensed under Apache-2.0 and MIT by the ext-apps project.
