# jEAP MCP Server

## What is the jEAP MCP Server?

The **jEAP MCP Server** (`jeap-mcp-service`) is a Spring Boot / jEAP
service that exposes a [Model Context Protocol](https://modelcontextprotocol.io)
endpoint for agentic development of jEAP applications. It gives AI assistants
(currently GitHub Copilot CLI) a reliable, version-aware view of the jEAP
platform so they can answer questions and generate code that uses the real
jEAP components, starters, and conventions — instead of relying on out-of-date
model knowledge.

### What it provides

1. **HTTP MCP server** (Spring AI, Streamable HTTP) reachable at:
    - `http://jeap-mcp.bit.admin.ch/jeap-mcp-service/mcp`

2. **Built-in MCP tools — `jeap_overview` and `jeap_version_overview`**
   `jeap_overview` returns the jEAP umbrella README, which lists every jEAP
   repository (services, libraries, starters) with a short description of its
   purpose. Use it to discover which jEAP component covers a given capability
   (messaging, audit, crypto, error handling, archrepo, deploymentlog,
   governance, message-type registry, etc.).
   `jeap_version_overview` returns the current versions of the jEAP parent,
   libraries, products, Spring components, and managed 3rd-party dependencies,
   cached and periodically refreshed from GitHub.

3. **MCP proxy mode (RAG over jEAP source code)**
   The deployed Docker image bundles the `project-rag` binary together with a
   prebuilt LanceDB index of the jEAP source code. The MCP server proxies the
   read-only RAG tools through its own endpoint, so clients only need to
   configure **one** MCP server to get both the umbrella knowledge and
   semantic search over the jEAP codebase.

   The total exposed surface is **10 tools = 2 local (`jeap_overview`,
   `jeap_version_overview`) + 6 RAG-proxy wrappers + 2 documentation tools**.
   Each wrapper has a fixed `jeap_*` name owned by `JeapRagTools.java`;
   upstream tool names never leak through.

    - `query_codebase` → `jeap_find_code_examples` — semantic search over the jEAP code & examples
   - `find_definition` → `jeap_find_definition` — locate the definition of a symbol
   - `find_references` → `jeap_find_references` — find all references to a symbol
   - `get_call_graph` → `jeap_get_call_graph` — explore the call graph around a function
   - `search_by_filters` → `jeap_search_by_filters` — advanced filtered search
   - `get_statistics` → `jeap_get_statistics` — index statistics

   The two **documentation** tools target the curated `docs/**` corpus:

   - `jeap_find_in_documentation` — search ONLY the jEAP documentation (overview,
     concepts, principles, library/service guides); returns whole documents.
     Prefer it for conceptual / how-to / "explain jEAP X" / architecture /
     migration questions, before `jeap_find_code_examples`. It searches the whole
     documentation (there is no repository filter); to focus on one repository,
     mention it in the query.
   - `jeap_get_document` — fetch the full text of a single doc by its index-local,
     repo-prefixed path (e.g. `jeap-messaging-outbox/docs/getting-started.md`); use it to read a
     full doc and follow its index-local cross-links.

   Write tools (`index_codebase`, `clear_index`) are **intentionally excluded**
   from the allowlist; the index is read-only at runtime.

4. **jEAP documentation as MCP Resources** — the curated `docs/**` corpus is also exposed through the MCP
   [Resources](https://modelcontextprotocol.io/specification/2025-11-25/server/resources) feature, as a
   context-sparing alternative to the tools above:

   - **`jeap-docs://index`** (static resource, `resources/list`) — the curated repository index (same
     content as `jeap_overview`), followed by a sitemap of ready-to-use, percent-encoded
     `jeap-docs://{ref}` URIs grouped by repository (so you can copy one directly instead of building it
     yourself). It is the single entry-point resource listed up front; there is deliberately **no**
     per-file resource listing, which would otherwise dump hundreds of `docs/**` files into every client's
     resource picker.
   - **`jeap-docs://{ref}`** (Resource Template, `resources/templates/list`) — covers every allow-listed
     doc file on demand. `ref` is the same repo-prefixed, index-local path `jeap_get_document` accepts
     (e.g. `jeap-messaging-outbox/docs/getting-started.md`, optionally with a `#section` anchor), but percent-encoded by
     the client into a single opaque URI segment (e.g.
     `jeap-docs://jeap-messaging-outbox%2Fdocs%2Fgetting-started.md`) — the MCP URI template syntax only supports one
     path segment per `{var}` placeholder (no literal `/`).
   - **Completion** (`completion/complete`) for the template's `ref` argument — a client can ask for
     suggestions as the value is typed, instead of already knowing (or guessing) the exact path.

   Both resources read through the same `DocsReader` / `docs/**` allow-list as the tools, so content and
   access rules are identical; only the MCP feature (Resource vs Tool) and the usage metric differ (see
   [docs/metrics.md](./metrics.md)).

### When to use it

Use the jEAP MCP server whenever your assistant needs to answer or act on:

- Any question that mentions **jEAP** or a **jeap-\*** repository name
- jEAP framework concepts: sequential inbox, transactional outbox,
  message-type registry, archrepo, deploymentlog, governance, …
- "Which starter / library should I use for X?" style questions
- "Show me how jEAP does Y" — the RAG tools search real source and examples

---

## How to use the jEAP MCP server

Once the server is registered with your AI client, your assistant will pick up
the new tools automatically. You generally **do not** call the tools by name —
just ask jEAP-related questions in natural language and the assistant will
route to the right tool.

### Tool reference

| Tool                      | Purpose                                                |
|---------------------------|--------------------------------------------------------|
| `jeap_overview`           | Returns the jEAP umbrella README (repository index)    |
| `jeap_version_overview`   | Returns current jEAP parent/library/product versions   |
| `jeap_find_code_examples` | Semantic RAG search over jEAP source code & examples   |
| `jeap_find_definition`    | Locate the definition of a symbol in the jEAP codebase |
| `jeap_find_references`    | Find references to a symbol in the jEAP codebase       |
| `jeap_get_call_graph`     | Explore the call graph around a function               |
| `jeap_search_by_filters`  | Filtered structured search                             |
| `jeap_get_statistics`     | Statistics about the indexed corpus                    |
| `jeap_find_in_documentation` | Search the curated jEAP docs (whole documents)      |
| `jeap_get_document`       | Fetch the full text of a single doc by its path        |

### Example prompts

- *"Which jEAP starter should I use to publish events transactionally from a Spring Boot service?"*
- *"How do I configure the jEAP sequential inbox? Show me an example."*
- *"Find all usages of `MessageTypeRegistry` in the jEAP codebase."*
- *"Where is `JeapOauth2WebSecurityConfig` defined and what does it expose?"*
- *"Explain the jEAP transactional outbox and how it guarantees delivery."* (docs)
- *"Read the jEAP messaging overview and follow its links to the outbox doc."* (docs)

### Verifying the endpoint manually

You can confirm the server is reachable with a raw MCP `initialize` call:

```bash
curl -i -X POST "http://jeap-mcp.bit.admin.ch/jeap-mcp-service/mcp" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{
    "jsonrpc":"2.0",
    "id":1,
    "method":"initialize",
    "params":{
      "protocolVersion":"2025-03-26",
      "capabilities":{},
      "clientInfo":{"name":"curl","version":"1.0.0"}
    }
  }'
```

A `200 OK` with a JSON-RPC response confirms the endpoint is up.

### Verifying resources manually

Once initialized (reuse the `mcp-session-id` header returned above), list and read the documentation
resources with raw JSON-RPC calls:

```bash
# resources/list -> the single curated "jeap-docs://index" entry point
curl -s -X POST ".../mcp" -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" -H "mcp-session-id: <session-id>" \
  -d '{"jsonrpc":"2.0","id":2,"method":"resources/list","params":{}}'

# resources/templates/list -> the "jeap-docs://{ref}" template covering every doc file
curl -s -X POST ".../mcp" -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" -H "mcp-session-id: <session-id>" \
  -d '{"jsonrpc":"2.0","id":3,"method":"resources/templates/list","params":{}}'

# resources/read -> a doc file addressed via the template; the ref is percent-encoded
# (jeap-messaging-outbox/docs/getting-started.md -> jeap-messaging-outbox%2Fdocs%2Fgetting-started.md)
curl -s -X POST ".../mcp" -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" -H "mcp-session-id: <session-id>" \
  -d '{"jsonrpc":"2.0","id":4,"method":"resources/read",
       "params":{"uri":"jeap-docs://jeap-messaging-outbox%2Fdocs%2Fgetting-started.md"}}'

# completion/complete -> suggested ref values for what's typed so far (here: "jeap-messaging")
curl -s -X POST ".../mcp" -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" -H "mcp-session-id: <session-id>" \
  -d '{"jsonrpc":"2.0","id":5,"method":"completion/complete",
       "params":{"ref":{"type":"ref/resource","uri":"jeap-docs://{ref}"},
                  "argument":{"name":"ref","value":"jeap-messaging"}}}'
```

---

## Add the jEAP MCP Server to GitHub Copilot CLI

The Copilot CLI supports MCP servers over HTTP transport. Register the
deployed jEAP MCP server with a single command:

```bash
copilot mcp add --transport http jeap-mcp-service \
  http://jeap-mcp.bit.admin.ch/jeap-mcp-service/mcp
```

### Verify the registration

List configured MCP servers:

```bash
copilot mcp list
```

You should see `jeap-mcp-service` in the list with status
`ready`. From the next Copilot CLI session, the `jeap_overview` and other
`jeap_*` tools will be available to the assistant.

### Removing the server

```bash
copilot mcp remove jeap-mcp-service
```

---

## Add the jEAP MCP Server to IntelliJ IDEA (GitHub Copilot plugin)

The GitHub Copilot plugin for JetBrains IDEs (IntelliJ IDEA, PyCharm, GoLand,
WebStorm, etc.) supports MCP servers in **Agent mode**. The jEAP MCP server is
registered via the plugin's `mcp.json` configuration file.

### Prerequisites

- A current version of the **GitHub Copilot** plugin installed and signed in
  (Settings → Plugins → Marketplace → search "GitHub Copilot").
- A Copilot subscription that grants access to **Copilot Chat — Agent mode**.

### Open the MCP configuration

1. Open the **GitHub Copilot Chat** tool window (View → Tool Windows → GitHub
   Copilot Chat, or click the Copilot icon in the right sidebar).
2. Switch the chat mode selector at the bottom of the chat panel to **Agent**.
3. Click the **tools / settings icon** in the chat panel and choose
   **Configure your MCP server** → **Add MCP Tools**.

   This opens (and creates, if missing) the per-user `mcp.json` file used by
   the JetBrains Copilot plugin.

### Add the jEAP MCP server entry

Add the following server entry to `mcp.json`. If the file already contains
other servers, merge the entry into the existing `servers` object.

```json
{
   "servers": {
      "jeap-mcp-service": {
         "type": "http",
         "url": "http://jeap-mcp.bit.admin.ch/jeap-mcp-service/mcp"
      }
   }
}
```

Save the file. The Copilot plugin picks up the change automatically; if it
doesn't, click **Refresh** in the MCP servers view or restart the chat
session.

### Verify the registration

In the Copilot Chat tool window (Agent mode), open the tools picker. You
should see the `jeap_*` tools listed under
`jeap-mcp-service`:

- `jeap_overview`
- `jeap_version_overview`
- `jeap_find_code_examples`
- `jeap_find_definition`
- `jeap_find_references`
- `jeap_get_call_graph`
- `jeap_search_by_filters`
- `jeap_get_statistics`
- `jeap_find_in_documentation`
- `jeap_get_document`

Ask a jEAP-related question (see [Example prompts](#example-prompts)) — the
agent will route to the appropriate tool automatically.

### Verifying the MCP Resources in the Copilot plugin

The jEAP documentation is also exposed as MCP Resources (`jeap-docs://index` and the `jeap-docs://{ref}`
template), independent of the tools above. In the Copilot Chat tool window (Agent mode), open the **Add
Context** / resource picker (the `#` or context icon in the chat input) and look for `jeap-mcp-service`.

**Known Copilot limitations, confirmed by manual testing** (server-side behavior is correct and verified
independently by raw JSON-RPC — see [Verifying resources manually](#verifying-resources-manually) above):

- The static `jeap-docs://index` resource is listed and attachable, and reading it works.
- The `jeap-docs://{ref}` **template is shown** (Copilot offers a `ref` input), but Copilot does **not**
  percent-encode the value before substituting it into the URI. Since the MCP URI template syntax only
  supports one opaque path segment per `{ref}` (no literal `/`), any real doc path — which always contains
  `/` — fails with a generic "Cannot read resources with current variables" error. This is a client-side
  encoding gap in Copilot, not a server defect (confirmed via `resources/read` with the same, unencoded URI:
  the request never reaches `JeapDocsResource`, it is rejected by the MCP SDK's URI-template routing before
  that).
- Copilot never calls `completion/complete` for the `ref` argument (no autocomplete suggestions appear
  while typing, and `jeap_mcp_resource_completion_calls_total` stays at 0 for Copilot traffic), even though the
  capability is implemented and independently verified to work correctly via raw JSON-RPC.

Net effect: for Copilot users, the reliable path to jEAP documentation remains the **Tool** surface
(`jeap_get_document`, `jeap_find_in_documentation`), not Resources. The Resources/Completion surface is
spec-compliant and works correctly with clients that implement it fully (e.g. Claude Code).

### Removing the server

Open `mcp.json` again via **Configure your MCP server** and delete the
`jeap-mcp-service` entry from the `servers` object.
