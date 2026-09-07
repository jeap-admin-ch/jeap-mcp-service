# AGENTS.md

This file provides guidance to coding agents when working with code in this repository.

## What this is

`jeap-mcp-service` is a **jEAP MCP server, built as a reusable Spring Boot library** (not a
standalone deployable), the same pattern `jeap-archrepo-service` uses:

- **`jeap-mcp-service-web`** — the actual application code: the `@Tool`-annotated jEAP tool
  surface, the docs pipeline, security/metrics config. No `spring-boot-maven-plugin`, no
  `mainClass`, no `Dockerfile`. Published as a plain library jar.
- **`jeap-mcp-service-instance`** — a thin, code-free `pom`-packaged parent. Declares a real
  dependency on `jeap-mcp-service-web`, so anything that inherits from it gets that dependency for
  free.
- **Instance repos** inherit `jeap-mcp-service-instance` as
  their Maven parent, add their platform-specific dependencies, configure
  `spring-boot-maven-plugin`'s `mainClass` (`ch.admin.bit.jeap.mcp.Application`), and hold their
  own `Dockerfile`. See [`docs/deployment.md`](docs/deployment.md) for the full contract an
  instance's `Dockerfile` must satisfy.

Its job: expose a fixed, jEAP-branded set of MCP tools (`jeap_*`) over Streamable HTTP so AI coding
agents can look up jEAP source, examples, and platform documentation directly — either backed by a
bundled RAG index (via an upstream `project-rag` MCP server every instance wires up over stdio) or
served straight off the filesystem (the docs tools).

## Build & test

Always use the Maven wrapper `./mvnw` (Java 25, Spring Boot).

Unlike `jeap-archrepo-service`, tests here need **no Docker daemon** — there's no Testcontainers
dependency in this repo. `McpServerIT` and friends boot a real embedded server
(`@SpringBootTest(webEnvironment = RANDOM_PORT)`) and drive it over HTTP via `McpClientHarness`
(wraps the Streamable-HTTP JSON-RPC handshake: `initialize` → session-id → subsequent calls), with
the docs pipeline pointed at filesystem fixtures under `src/test/resources/`.

```bash
./mvnw clean install                                          # full reactor build + all tests
./mvnw -q -pl jeap-mcp-service-web -am test                   # build one module (+ its deps) and test it
./mvnw -pl jeap-mcp-service-web test -Dtest=JeapRagToolsTest   # single test class
./mvnw -pl jeap-mcp-service-web test -Dtest=JeapRagToolsTest#findCodeExamplesDelegatesToUpstream  # single method
```

Note: a full `clean install` regenerates `THIRD-PARTY-LICENSES.md` via the license plugin, but in a
local/offline run it can produce an **incomplete** file. Do not commit that regeneration — revert
it (`git checkout -- THIRD-PARTY-LICENSES.md`); the file is maintained by the CI/release pipeline.

## Architecture

### The jEAP tool surface (`jeap-mcp-service-web/.../tool/`)

Ten `@Tool`-annotated methods across four classes, wired into one `ToolCallbackProvider` in
`McpToolConfig`:

- `JeapOverviewTool` — `jeap_overview`, a static, RAG-free repository index.
- `JeapVersionOverviewTool` — `jeap_version_overview`, cached/periodically refreshed from GitHub.
- `JeapRagTools` — `jeap_find_code_examples`, `jeap_find_definition`, `jeap_find_references`,
  `jeap_get_call_graph`, `jeap_search_by_filters`, `jeap_get_statistics`, `jeap_find_in_documentation`:
  each declares its own fixed input schema and proxies to the matching tool on an upstream
  `project-rag` MCP server (connected over stdio, one per instance) via an injected
  `McpSyncClient`.
- `JeapDocsTool` — `jeap_get_document`: a pure filesystem read (no upstream call) of a single doc
  by its index-local, repo-prefixed path, via `DocsReader`/`DocPathPolicy`/`DocRef` (allow-listed,
  traversal-safe path resolution — no absolute paths, no `..`).

**Invariant, enforced by convention not code:** never set
`spring.ai.mcp.client.toolcallback.enabled=true` (or `spring.ai.mcp.server.expose-mcp-client-tools`).
Either would re-publish every raw upstream `project-rag` tool — including write tools like
`index_codebase`/`clear_index` — alongside the `jeap_*` wrappers. The wrappers in `JeapRagTools`
are the only sanctioned surface; there is no auto-proxy.

The upstream client is selected by matching its MCP client-info name against
`<spring.ai.mcp.client.name> - project-rag`. If `spring.ai.mcp.client.enabled=true` but no matching
client is connected at startup, `JeapRagTools`'s bean construction fails fast; if the client is
disabled, calls fail at call time with a clear `IllegalStateException` instead.

Every `JeapRagTools` method is `@Retryable` (`jeap.mcp.upstream.retry.*`, default 3 attempts /
500ms backoff): the MCP Java SDK's `StdioClientTransport` can throw a transient
`RuntimeException("Failed to enqueue message")` under back-pressure, and recovery is left to the
caller. `IllegalStateException` (misconfiguration, not a transport hiccup) is excluded from retry.

### Security

`McpSecurityConfig` installs a `HIGHEST_PRECEDENCE` `SecurityFilterChain` matched to `/mcp/**` that
`permitAll()`s everything under it — the MCP endpoint itself is intentionally unauthenticated at
this layer (per-instance network/gateway controls do the gating). Everything else falls through to
`jeap-spring-boot-security-starter`'s own catch-all chain, gated by
`jeap.security.oauth2.resourceserver.system-name`.

### Metrics

`McpMetrics` records per-tool-call timing (`@Timed`, tag = tool name) and, for RAG-proxy tools
whose response carries a `results[]` array, counts each returned chunk tagged by tool/file
extension/area (`docs`|`code`, via `DocPaths`). Metrics recording never throws — a chunk that looks
like JSON but fails to parse increments a parse-failure counter instead of breaking the tool call.

## Versioning & Conventions

- Semantic Versioning; all changes documented in [CHANGELOG.md](./CHANGELOG.md) (Keep a Changelog
  format) — entries are typically grouped as `### Dependencies` / `### Fixed` / `### Security`.
- `setPomVersions.sh` updates the version across all module POMs.
- When working on a feature branch, increase the version to `x.y.z-SNAPSHOT` in the POMs.
- When bumping the version, also update the changelog, and the version/date in `publiccode.yml`.
- When the version on a feature branch has not yet been bumped compared to master, ask the user if
  a major, minor, or patch version bump should be performed, and update the version accordingly.
