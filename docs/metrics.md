# jEAP MCP Server — metrics documentation

This document describes the metric series that help estimating **jEAP documentation usage**, what each one
means, and the load-bearing caveat that **retrieval ≠ usage**. All series are Micrometer meters exported
to Prometheus.

## Series

| Series | Type | Tags | Meaning |
|---|---|---|---|
| `jeap_mcp_tool_calls_seconds_count{tool}` | timer count | `tool` | Number of MCP tool invocations. `tool="jeap_find_in_documentation"` and `tool="jeap_get_document"` are the **strongest cheap intent signals** that an agent is using the docs. |
| `jeap_mcp_upstream_chunks_fetched_total{extension,area,tool}` | counter | `extension`, `area` (`docs`\|`code`), `tool` | Upstream RAG **result chunks** returned, attributed by file extension and area. `area="docs"` chunks of `tool="jeap_find_in_documentation"` are the doc-retrieval signal. Counts **chunks**, not returned documents (the tool over-fetches `K*3` chunks before dedup-to-K), so read it as a retrieval **proxy**. |
| `jeap_mcp_docs_fetched_total{extension,area}` | counter | `extension`, `area` | Deliberate **document fetches** via `jeap_get_document`. A fetch is a document, not a RAG chunk, so it has its own counter — `jeap.mcp.upstream.chunks.fetched` never mixes in fetches. `area` is derived from the path (a repo-root `README.md` counts as `area="code"`). |
| `jeap_mcp_resource_reads_total{resource,extension,area}` | counter | `resource` (`jeap_docs_index`\|`jeap_doc`), `extension`, `area` | MCP **resource** reads (`resources/read`) of the `jeap-docs://index` static resource and the `jeap-docs://{ref}` resource template. Kept separate from `jeap_mcp_docs_fetched_total` so Resource-surface usage (Copilot IDE / other MCP clients reading docs as resources) can be analyzed independently of the Tool surface (`jeap_get_document`). |
| `jeap_mcp_resource_completion_calls_total{resource,matched}` | counter | `resource` (`jeap_doc`), `matched` (`true`\|`false`) | `completion/complete` requests for a resource template's argument (currently only `jeap-docs://{ref}`, resource `jeap_doc`). A completion request is discovery/intent — a client asking "what values exist for `ref`" — not a document read; kept separate from `jeap_mcp_resource_reads_total`. Tagged by the same `resource` name so it can be compared 1:1 against `jeap_mcp_resource_reads_total`. `matched=false` means the typed prefix had no suggestions at all (dead end for the client). The typed value itself is deliberately not a tag (unbounded cardinality). |
| `jeap_mcp_upstream_parse_failures_total{tool}` | counter | `tool` | A successful (non-error) upstream response could not be parsed for the `jeap.mcp.upstream.chunks.fetched` metric. Should be ~0; a rising value means upstream response-schema drift (the tool call itself still succeeds). |
| `jeap_mcp_tool_arguments_rejected_total{tool,argument}` | counter | `tool`, `argument` | A `jeap_*` tool argument rejected by server-side validation (over-long free text, oversized list, an over-long list element, or a `file_path` that is not absolute / escapes the indexed source root). The `jeap_*` tools are reachable **anonymously** - with no caller identity to inspect, this is the primary signal for telling deliberate probing/abuse of the argument limits apart from normal traffic. A sustained non-zero rate (rather than the occasional legitimate typo) is worth investigating. |
| `jeap_mcp_tool_arguments_clamped_total{tool,argument}` | counter | `tool`, `argument` (`limit`\|`depth`) | A `limit`/`depth` argument that actually exceeded its cap and was silently capped rather than rejected. Unlike a rejection this never surfaces in the tool's own response, so it's the only signal for the original abuse case this server hardens against - an anonymous caller trying to force an unbounded query against `project-rag`. |

Notes:
- `project` is **never** a Prometheus label (it stays offline). A `docs/**` file is indexed **once**, in the
  artificial `jeap-docs` project only — the per-repo (code) projects no longer index docs. So `area="docs"`
  chunks come essentially only from `jeap_find_in_documentation`; the code RAG tools
  (`jeap_find_code_examples`, `jeap_search_by_filters`, …) no longer return `docs`-area chunks, and there is
  no double-counting inflation of the absolute chunk counts.
- `area` is derived from the file path: a path with a `docs` segment → `docs`, else `code`.

## Useful PromQL

Doc share of all retrieved chunks (how much of what we return is documentation):

```promql
sum(rate(jeap_mcp_upstream_chunks_fetched_total{area="docs"}[1d]))
/
sum(rate(jeap_mcp_upstream_chunks_fetched_total[1d]))
```

Doc tool usage (intent), per tool:

```promql
sum by (tool) (rate(jeap_mcp_tool_calls_seconds_count{tool=~"jeap_find_in_documentation|jeap_get_document"}[1d]))
```

Deliberate fetches by extension:

```promql
sum by (extension) (rate(jeap_mcp_docs_fetched_total[1d]))
```

Resource-surface reads (Copilot IDE / other MCP clients using `resources/read`), by resource:

```promql
sum by (resource) (rate(jeap_mcp_resource_reads_total[1d]))
```

Completion usage for the `ref` argument (are clients asking for suggestions at all), and how often that's a dead end:

```promql
sum by (matched) (rate(jeap_mcp_resource_completion_calls_total[1d]))
```

Completion intent vs. actual resource reads, per resource (does completion lead to a read):

```promql
sum by (resource) (rate(jeap_mcp_resource_completion_calls_total[1d]))
/
sum by (resource) (rate(jeap_mcp_resource_reads_total[1d]))
```

Parse-failure watch (should stay flat near zero):

```promql
sum by (tool) (increase(jeap_mcp_upstream_parse_failures_total[1d]))
```

Rejected-argument watch (abuse/probing signal on the anonymous surface - should stay low and flat; a
spike or sustained climb is worth investigating):

```promql
sum by (tool, argument) (increase(jeap_mcp_tool_arguments_rejected_total[1h]))
```

Clamped-argument watch (same rationale, but for `limit`/`depth` capped rather than rejected):

```promql
sum by (tool, argument) (increase(jeap_mcp_tool_arguments_clamped_total[1h]))
```

## Retrieval ≠ usage (the caveat to keep front of mind)

Every **server-side** metric measures what was *returned* or *fetched*, not whether the agent actually
*used* it in its answer. The cleanest "the agent reached for the docs" signals are the **tool-call counts**
(`jeap_mcp_tool_calls_seconds_count{tool="jeap_find_in_documentation"|"jeap_get_document"}`) and the
**`jeap_mcp_docs_fetched_total`** fetch counter. The `jeap.mcp.upstream.chunks.fetched` series is a retrieval proxy (it counts
over-fetched chunks). True usage attribution requires offline transcript correlation, which is out of scope
here.

## Estimate documentation usage
1. `jeap_mcp_tool_calls_seconds_count{tool="jeap_get_document"}` and `{tool="jeap_find_in_documentation"}` — intent.
2. `jeap_mcp_docs_fetched_total` — deliberate fetches via the Tool surface, by extension/area.
3. `jeap_mcp_resource_reads_total` — deliberate reads via the Resource surface (`resources/read`), by resource/extension/area.
4. `jeap_mcp_resource_completion_calls_total` — discovery/intent via the Completion surface (`completion/complete`), by resource/matched.
5. `jeap_mcp_upstream_chunks_fetched_total{area="docs"}` — doc chunks retrieved (proxy).
