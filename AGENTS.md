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
  each declares its own fixed input schema, validates/clamps its arguments via `JeapRagProperties`,
  and delegates the actual upstream call to `UpstreamRagInvoker`, which proxies to the matching
  tool on an upstream `project-rag` MCP server (connected over stdio, one per instance) via an
  injected `McpSyncClient` and records chunk/parse-failure metrics for the result. `JeapRagTools`
  constructs its own `UpstreamRagInvoker` in its constructor (not a separate Spring bean) — the
  split exists to keep the MCP-transport/metrics concern out of the file that shapes/validates
  tool arguments.
- `JeapDocsTool` — `jeap_get_document`: a pure filesystem read (no upstream call) of a single doc
  by its index-local, repo-prefixed path, via `DocsReader`/`DocPathPolicy`/`DocRef` (allow-listed,
  traversal-safe path resolution — no absolute paths, no `..`; caller-controlled paths, and every
  value derived from them, are sanitized via the shared `LogSanitizer` before being logged, to
  prevent log forging via embedded control characters — including the Unicode line separators
  `\p{Cntrl}` alone doesn't cover, which some log viewers/JSON-log consumers also treat as line
  breaks. `RagFilePathPolicy` uses the same utility for its own rejection logs).

**Invariant, enforced by convention not code:** never set
`spring.ai.mcp.client.toolcallback.enabled=true` (or `spring.ai.mcp.server.expose-mcp-client-tools`).
Either would re-publish every raw upstream `project-rag` tool — including write tools like
`index_codebase`/`clear_index` — alongside the `jeap_*` wrappers. The wrappers in `JeapRagTools`
are the only sanctioned surface; there is no auto-proxy.

The upstream client is selected by matching its MCP client-info name against
`<spring.ai.mcp.client.name> - project-rag`. If `spring.ai.mcp.client.enabled=true` but no matching
client is connected at startup, `UpstreamRagInvoker`'s construction (inside `JeapRagTools`'s
constructor) fails fast; if the client is disabled, calls fail at call time with a clear
`IllegalStateException` instead.

`UpstreamRagInvoker.call` retries the upstream call itself via a plain `RetryTemplate`
(`jeap.mcp.upstream.retry.*`, default 3 attempts / 500ms backoff): the MCP Java SDK's
`StdioClientTransport` can throw a transient `RuntimeException("Failed to enqueue message")` under
back-pressure, and recovery is left to the caller. `IllegalStateException` (misconfiguration, not a
transport hiccup) is excluded from retry. This is deliberately not `@Retryable` on the `@Tool`
methods: those methods validate/clamp arguments (recording the `arguments.clamped`/`.rejected`
metrics below) before ever reaching `call`, and an AOP-proxied retry there would re-run that
argument shaping — and re-record its metrics — on every retry attempt instead of once per request.

`JeapRagProperties` (`jeap.mcp.rag.*`) is the single place that decides what's "too much" for a
`jeap_*` tool argument, since the tools are reachable anonymously (see Security below): it clamps
`limit`/`depth` server-side to `null` when non-positive (so `Args#put` omits the key and upstream
applies its own default — a non-positive value forwarded as-is would let a caller bypass the cap,
since some upstream tools treat it as "unlimited") and rejects (throws
`JeapToolValidationException`, never silently truncates) over-long free-text arguments, oversized
filter lists, and individual over-long list elements. Its
`validate(String jeapToolName, McpMetrics metrics, Runnable checks)` helper turns that exception
into a returned error message in one line — and records `jeap.mcp.tool.arguments.rejected` (tagged
by tool + argument) so a rejection is visible on the anonymous surface, not just returned to the
caller — so each `@Tool` method's body reads
`ragProperties.validate(TOOL_X, metrics, () -> { ...checks... }).orElseGet(() -> ...call upstream...)`
instead of repeating a try/catch.

`RagFilePathPolicy` rejects a `file_path` (on `findDefinition`/`findReferences`/`getCallGraph`)
that is not absolute, or that resolves outside the indexed source root, before it reaches
`UpstreamRagInvoker`. The documented calling convention (`jeap_find_code_examples`' `root_path` +
`file_path` result fields, see the instance repos' own deployment smoke tests, e.g.
`AfterDeploymentSmokeTestIT.locationBasedToolsWork` — that test lives in each instance repo, not
here) always produces an absolute path, since `root_path` is itself always absolute (e.g.
`/jeap/src/jeap-messaging`); a relative `file_path` is rejected outright rather than resolved,
because `JeapRagTools` forwards the caller's original string unchanged, and `project-rag` would
resolve a relative value against its own process cwd — not against the source root this policy
checks — letting a relative value bypass containment entirely. See `RagFilePathPolicy`'s javadoc.
`project-rag`'s own `create_file_info` (see `jeap-project-rag`'s
`src/client/mod.rs`) canonicalizes and reads whatever `file_path` it is given with no containment
check of its own — unguarded, this is an arbitrary-file-read primitive reachable anonymously.
Reuses `JeapDocsProperties#docsRoot()` (default `/jeap/src`) rather than a separate property: every
instance's `Dockerfile` `COPY`s the `jeap-project-rag-preindexed` image's `/jeap/src` verbatim, and
that image clones every indexed repo to `/jeap/src/<project>` — the exact same root `DocPathPolicy`
already anchors the docs tools to. Checked lexically (`Path#normalize()`, no `toRealPath()`/symlink
resolution, no existence requirement) rather than like `DocPathPolicy`: a `file_path` that is
contained but simply wrong must still reach `project-rag` so its own "not found" message comes back
unchanged, and symlink escapes are already ruled out upstream by `jeap-index.sh` stripping every
symlink before indexing.

### Security

`McpSecurityConfig` installs a `HIGHEST_PRECEDENCE` `SecurityFilterChain` matched to `/mcp/**` that
`permitAll()`s everything under it — the MCP endpoint itself is intentionally unauthenticated at
this layer (per-instance network/gateway controls do the gating). Everything else falls through to
`jeap-spring-boot-security-starter`'s own catch-all chain, gated by
`jeap.security.oauth2.resourceserver.system-name`.

That same chain also installs `McpRequestSizeFilter` (`jeap.mcp.max-request-body-bytes`, default
1 MiB), rejecting an oversized `/mcp/**` request body with `413` before it is read/parsed —
`JeapRagProperties`' per-argument caps bound individual fields, not the raw JSON-RPC request body
itself.

### Metrics

`McpMetrics` records per-tool-call timing (`@Timed`, tag = tool name) and, for RAG-proxy tools
whose response carries a `results[]` array, counts each returned chunk tagged by tool/file
extension/area (`docs`|`code`, via `DocPaths`). Metrics recording never throws — a chunk that looks
like JSON but fails to parse increments a parse-failure counter instead of breaking the tool call.
`jeap.mcp.tool.arguments.rejected` (tag = tool + argument) counts server-side validation rejections,
and `jeap.mcp.tool.arguments.clamped` (same tags) counts a `limit`/`depth` actually capped — see
`docs/metrics.md` for the full series list and a suggested PromQL watch.

A rejected/denied caller input (an over-long argument, a `file_path`/docs path that escapes its
root, ...) is logged at `warn`, not `error` — an anonymous caller can trigger unbounded volume of
these by construction, so `error` is reserved for genuine server-side faults (e.g. an absent docs
root) rather than ordinary bad input, keeping error-rate alerting meaningful.

## Versioning & Conventions

- Semantic Versioning; all changes documented in [CHANGELOG.md](./CHANGELOG.md) (Keep a Changelog
  format) — entries are typically grouped as `### Dependencies` / `### Fixed` / `### Security`.
- `setPomVersions.sh` updates the version across all module POMs.
- When working on a feature branch, increase the version to `x.y.z-SNAPSHOT` in the POMs.
- When bumping the version, also update the changelog, and the version/date in `publiccode.yml`.
- When the version on a feature branch has not yet been bumped compared to master, ask the user if
  a major, minor, or patch version bump should be performed, and update the version accordingly.
