# Configuration

Most properties `jeap-mcp-service-web` needs are shipped as library defaults and don't need to be
set by the instance at all - see [How defaults work](#how-defaults-work) below. Only the properties
below marked **required** must be supplied by the **instance** - the microservice that embeds
`jeap-mcp-service-web` - in its own `application.yml`.

## Core

| Property                                            | Required | Default | Description                                                                                                                                                                                            |
|------------------------------------------------------|----------|---------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `jeap.mcp.knowledge.overview-docs-location`         | yes      | -       | Filesystem path to the indexed jEAP source (`jeap_get_document`, `jeap_overview`) - wherever the instance's `Dockerfile` `COPY --from`'d `/jeap/src` to, see [Deployment](deployment.md)              |
| `jeap.security.oauth2.resourceserver.system-name`   | yes      | -       | The instance's OAuth2/IAM system identifier used by the jEAP security starter to validate incoming tokens. Instance-specific by definition - never set at the library level                          |
| `server.servlet.context-path`                       | no       | `/${spring.application.name}` | Must resolve to `/${spring.application.name}`. Without it the app serves at `/`, breaking every route/health-check path that assumes this prefix                                     |
| `spring.ai.mcp.client.toolcallback.enabled`         | no       | `false` | Must stay `false`. The actual switch preventing raw upstream `project-rag` tools (`index_codebase`, `clear_index`, ...) from leaking through the MCP server - shipped as a library default rather than merely documented as required, so it can't be silently skipped |
| `spring.ai.mcp.server.name`                         | no       | `jeap-mcp-server` | The MCP server identity clients see                                                                                                                                                                |
| `spring.ai.mcp.server.version`                      | no       | `1.0.0` | The MCP server version string clients see                                                                                                                                                              |
| `spring.ai.mcp.server.instructions`                 | no       | (see `jeapMcpServiceDefaultProperties.properties`) | The instructions text clients see                                                                                                                                              |
| `spring.ai.mcp.client.enabled`                      | no       | `false` | Turns the `project-rag` stdio connection on. An instance flips it to `true` once its Dockerfile/deployment provides `project-rag` - directly as a property, or via the equivalent `SPRING_AI_MCP_CLIENT_ENABLED` env var (the standard Spring Boot relaxed-binding override, handy in containers) |

## How defaults work

`jeap-mcp-service-web` has no `application.yml` of its own. Spring Boot only loads *one*
`application.yml` from the classpath root - the main application's own - so a copy bundled inside
this module would be silently ignored once it's packaged as a dependency inside an instance's fat
jar. Instead, the properties above that have a default are shipped in
`jeapMcpServiceDefaultProperties.properties` and loaded via `@PropertySource`
(`ch.admin.bit.jeap.mcp.config.McpDefaultPropertiesConfig`) - the same pattern every jEAP reusable
microservice uses (e.g. `jeap-archrepo-service`'s `archrepoDefaultProperties.properties`). Unlike a
bundled `application.yml`, a `@PropertySource` is added to the Spring `Environment` regardless of
packaging, at low enough precedence that an instance's own `application.yml` can still override any
individual key without having to redeclare the rest.

Only genuinely library-owned, same-for-every-instance values are defaulted this way. Deployment
paths and per-instance identifiers/secrets (`jeap.mcp.knowledge.overview-docs-location`,
`jeap.security.oauth2.resourceserver.system-name`) are deliberately left out and must be supplied by
each instance.

## Version overview

| Property                                              | Required | Default                                                                                   | Description                                                                                                                                                |
|-------------------------------------------------------| -------- |-------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `jeap.mcp.version-overview.source-url`                | no       | `https://raw.githubusercontent.com/jeap-admin-ch/jeap/main/docs/jeap-version-overview.md` | Where `jeap_version_overview` fetches from. Override if the instance's network can't reach GitHub - any internally-reachable mirror of the same file works |
| `jeap.mcp.version-overview.refresh-interval-millis`   | no       | `3600000` (1 hour)                                                                        | How often the cache refreshes. The first fetch happens 1 second after startup, on a background thread - never blocks startup                               |

Both defaults are applied inline in code (`@Value("${...:default}")`), not via the properties file
above - they work correctly regardless of packaging already.

The fetch fails closed: a slow/unreachable `source-url` falls back to the last-cached content, or a
"currently being loaded" placeholder if nothing has ever been cached - never a startup failure.

## Management/actuator port

Only relevant if the instance puts actuator on a separate port from the main server
(`management.server.port`) - for example to keep metrics/health traffic off the same port as
application traffic.

| Property                      | Required                                | Default | Description                                                                                                                                                                                                                                                                                                                                                                                           |
|-------------------------------|-----------------------------------------| ------- |-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `management.server.base-path` | yes, if `management.server.port` is set | -       | Must match `server.servlet.context-path`. Spring Boot does not mirror the main context path onto a separate management server by default - it serves actuator at that port's bare root instead - so a readiness/liveness probe built as `<context-path>/actuator/health/...` against the management port 404s forever, with the application itself running and healthy, unless this is set explicitly |

## Related

- [Deployment](deployment.md) - the `Dockerfile` contract each instance repo needs to satisfy
- [Usage](usage.md)
- [Metrics](metrics.md)
