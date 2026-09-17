# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.4.0] - 2026-09-18

### Added
- Server-side caps (`jeap.mcp.rag.max-limit`, `jeap.mcp.rag.max-depth`) on the `limit`/`depth` arguments
  of `jeap_find_code_examples`, `jeap_find_references`, `jeap_search_by_filters` and `jeap_get_call_graph`,
  so an anonymous caller can no longer force an unbounded query against the upstream `project-rag` process.
- Server-side validation (`jeap.mcp.rag.max-text-length`, `jeap.mcp.rag.max-list-size`) rejecting
  over-long free-text arguments (`query`, `path`, `file_path`, `project`) and oversized filter lists
  (`file_extensions`, `languages`, `path_patterns`) with a clean tool error, instead of forwarding them
  to the upstream `project-rag` process.
- Explicit instructions to the calling agent to treat every tool result as untrusted reference data,
  never as instructions to follow, mitigating indirect prompt injection via retrieved content.
- `jeap.mcp.tool.arguments.rejected` metric (tags: `tool`, `argument`), recorded by
  `JeapRagProperties#validate` on every rejection. See `docs/metrics.md`.
- `jeap.mcp.tool.arguments.clamped` metric (tags: `tool`, `argument`), recorded whenever a
  `limit`/`depth` argument actually exceeded its cap.
- `jeap.mcp.max-request-body-bytes` (default 1 MiB): a new `McpRequestSizeFilter` on `/mcp/**`
  rejects a request whose declared `Content-Length` exceeds it, with `413`, before the body is
  read. `jeap.mcp.rag.max-text-length`/`max-list-size` only bound individual argument fields, not
  the raw JSON-RPC request body.
- Shared `LogSanitizer` (replaces near-identical private copies in `DocPathPolicy` and
  `RagFilePathPolicy`), now also covering the Unicode line separators (NEL, `U+2028`, `U+2029`)
  that `\p{Cntrl}` alone misses.
- `docs/configuration.md` now documents `jeap.mcp.rag.*` and `jeap.mcp.docs.docs-root`.
  `RagFilePathPolicy` also logs its effective source root once at startup.

### Fixed
- `DocPathPolicy` no longer logs attacker-controlled documentation paths verbatim, preventing log
  forging via embedded control characters (CR/LF) in `jeap_get_document`'s `path` argument.
- `jeap_find_definition`/`jeap_find_references`/`jeap_get_call_graph`'s `file_path` argument is now
  rejected (new `RagFilePathPolicy`) unless it is absolute and resolves inside the indexed source
  root, before being forwarded to the upstream `project-rag` process. `project-rag` canonicalizes
  and reads whatever `file_path` it is given with no containment check of its own, so an anonymous
  caller could previously read arbitrary files reachable by the container's runtime user (e.g.
  `file_path: "/etc/passwd"`), with matching content surfacing back through
  `jeap_find_definition`'s parsed `signature`/`doc_comment` fields when the target happened to
  parse as a supported language. Only absolute `file_path` values are accepted, matching the
  documented `root_path + "/" + file_path` calling convention.
- `RagFilePathPolicy`'s rejection log no longer forges log lines via the resolved path: it
  sanitized the caller's raw input but logged the computed path unsanitized, and CR/LF survive
  `Path`/`normalize()` on Unix.
- `JeapRagProperties#clampLimit`/`#clampDepth` now map a non-positive `limit`/`depth` to `null`
  (omitted from the upstream request) instead of forwarding it unchanged, since some upstream
  tools treat a non-positive value as "unlimited".
- `JeapRagProperties#requireWithinSize` now also checks each list element's length, not just the
  list's element count.
- `DocPathPolicy` now logs caller-caused rejections (absolute path, `..` segment, root escape,
  not-on-allow-list) at `warn` instead of `error`, matching `RagFilePathPolicy` - these are
  ordinary bad input on an anonymous endpoint, not server faults.
- `DocPathPolicy`'s not-found log line no longer forges log lines via the upstream
  `NoSuchFileException` message: it sanitized the caller's raw input but logged the exception's
  `toString()` (which echoes that same input) unsanitized.
- `JeapRagProperties#requireWithinSize` now records `jeap.mcp.tool.arguments.rejected` with the
  bare argument name (e.g. `file_extensions`) instead of an index-suffixed name (e.g.
  `file_extensions[3]`), so the metric can be summed by (tool, argument) as documented instead of
  fragmenting into one series per rejected index.
- `jeap.mcp.tool.arguments.clamped` is no longer over-counted on upstream retries: clamping now
  happens once per external request in `UpstreamRagInvoker`'s plain `RetryTemplate`-based retry,
  instead of inside the `@Tool` method body that a `@Retryable` proxy used to re-run on every
  retry attempt.
- `McpRequestSizeFilter` now also rejects a `/mcp/**` request sent with `Transfer-Encoding: chunked`.
  Such a request declares no `Content-Length` (`getContentLengthLong()` returns `-1`), which
  previously slipped past the `> maxBytes` check entirely and let an anonymous caller stream an
  unbounded body straight into Jackson - exactly what this filter exists to prevent.

### Changed
- `JeapRagTools` internals split for readability: upstream MCP invocation/metrics now live in a
  separate `UpstreamRagInvoker` collaborator, and per-tool argument validation now goes through
  `JeapRagProperties#validate(String, McpMetrics, Runnable)` instead of a repeated try/catch.

## [2.3.0] - 2026-09-17

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 41.5.1 → 41.7.0 (minor)

## [2.2.1] - 2026-09-17

### Removed
- Removed the `java-uuid-generator.version` property: it pinned the managed version to 5.1.0 while the parent is on 5.2.0, and no module declares the dependency. The managed version now follows `jeap-internal-spring-boot-parent` again.

## [2.2.0] - 2026-09-16

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 41.3.0 → 41.5.1 (minor)

## [2.1.0] - 2026-09-15

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 41.1.0 → 41.3.0 (minor)

## [2.0.0] - 2026-09-13

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.11.0 → 41.1.0 (major)

## [1.0.0] - 2026-09-11

### Changed

-  Initial Version
