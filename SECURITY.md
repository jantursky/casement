# Security policy

Casement runs content from MCP servers, so its sandbox is the part of the project that matters most for security.

## Supported versions

Only the latest release of `io.github.jantursky:casement-core`, `casement-compose` and `casement-mcp-sdk` gets fixes.

## Reporting a vulnerability

Please don't open a public issue. Use GitHub's [private vulnerability reporting](https://github.com/jantursky/casement/security/advisories/new), or email jan.tursky@gmail.com with a description and, if you can, an app document that reproduces it.

You'll get an answer within a week. Once a fix is released, the advisory is published with credit, unless you'd rather stay anonymous.

## What counts

A bug that lets an app do something the host policy denies is a vulnerability. Examples: reading outside its origin, reaching a domain its CSP does not list, navigating or opening a window without a `BlockedAction`, calling a tool that is not visible to apps, or using a permission the policy did not grant.

The known gaps are listed under "Not covered yet" in the README. They are limits, not secrets, but a way to make one worse is still worth reporting.

## Things to know

- The library makes no network calls of its own. Your client makes them.
- It collects no data and needs no permissions beyond what the apps you choose to grant.
- The desktop renderer has weaker guarantees than the other three. Treat apps on the desktop as less isolated.
