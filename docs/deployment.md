# Building a deployable image

`jeap-mcp-service` doesn't ship a `Dockerfile` — there's no single deployable image for it. It's
built as a **reusable service**, the same pattern every jEAP reusable microservice uses (e.g.
`jeap-archrepo-service`):

- **`jeap-mcp-service-web`** — the actual application code (tools, RAG-proxy wrappers, docs lookup).
  No main class, no platform-specific dependencies, not directly runnable. Published as a plain
  library jar.
- **`jeap-mcp-service-instance`** — a thin, code-free parent POM. Declares a real dependency on
  `jeap-mcp-service-web`, so anything that inherits from it gets that dependency for free.
- **Instance repos** inherit `jeap-mcp-service-instance` as their Maven parent, add whichever
  platform-specific dependencies they need, configure `spring-boot-maven-plugin`'s `mainClass`
  themselves (pointing at `ch.admin.bit.jeap.mcp.Application`, from `jeap-mcp-service-web`), and
  hold their own `Dockerfile`. A plain `mvn package` in the instance repo produces its own
  executable jar — no jar-copying between repos, just normal Maven dependency resolution once
  `jeap-mcp-service-web`/`-instance` are published (`mvn deploy`) to the internal repository.

This doc describes the contract an instance repo's `Dockerfile` needs to satisfy, split into what's
common to every instance and what's genuinely platform-specific.

## Common to every instance

- **The preindexed jEAP knowledge base.** Alias the public
  [`jeap-project-rag-preindexed`](https://github.com/jeap-admin-ch/jeap-project-rag-preindexed)
  image as a build stage and `COPY --from` it:
  - `/usr/local/bin/project-rag` — the MCP server binary this app connects to over stdio
    (see `spring.ai.mcp.client.stdio.connections.project-rag` in the instance's own
    `application.yml`).
  - `.local/share/project-rag` and `.cache/project-rag` — the prebuilt LanceDB index and its cache.
  - `models/all-MiniLM-L6-v2` — the embedding model, pre-downloaded so no network access is needed
    at runtime.
  - `/jeap/src` — the indexed jEAP source, read at the path configured by
    `jeap.mcp.knowledge.overview-docs-location`.
- **The jar.** Each instance repo's own `mvn package` produces it (named per that repo's
  `<finalName>`) — nothing to copy in from elsewhere.
- **Turning the RAG backend on**: set `spring.ai.mcp.client.enabled=true` — off by default in
  `jeap-mcp-service-web`'s own library defaults, so an instance has to opt in once `project-rag` is
  actually present in its image. A plain property in the instance's `application.yml` works; the
  equivalent `SPRING_AI_MCP_CLIENT_ENABLED` env var is just the standard Spring Boot
  relaxed-binding override, handy for setting it per-deployment without rebuilding the image.
  Also required: `PROJECT_RAG_MODEL_PATH`, `PROJECT_RAG_LANCEDB_PATH` (must match wherever the
  preindexed image's content actually landed after the `COPY --from` above).
- **Read access** to whatever paths the preindexed image's content got copied to (it's a static,
  pre-built index — nothing writes to it at runtime, so plain world-readability is enough; no
  runtime user needs to *own* it).
- **The instance's own required properties.** Most of `jeap-mcp-service-web`'s configuration ships
  as library defaults and needs no action from the instance. A handful of genuinely instance-specific
  properties (deployment paths, IAM identifiers) still need to be set explicitly — see
  [Configuration](configuration.md) for the full, short list.

### Example Dockerfile

```dockerfile
# Stage 1: alias the preindexed image so we can COPY from it. Public GHCR image.
ARG PREINDEXED_TAG=latest
FROM ghcr.io/jeap-admin-ch/jeap-project-rag-preindexed:${PREINDEXED_TAG} AS rag

# Stage 2: a Java 25 runtime base, enriched with project-rag + index. Creates the non-root
# "appuser" account the COPY steps below expect - swap for whatever your own base image already
# provides, if it provides one.
FROM amazoncorretto:25-al2023

RUN dnf install -y --setopt=install_weak_deps=False shadow-utils ca-certificates && \
    groupadd -r appuser && \
    useradd -r -g appuser -m -d /home/appuser appuser && \
    dnf clean all

COPY --from=rag /usr/local/bin/project-rag /usr/local/bin/project-rag

COPY --from=rag /home/raguser/.local/share/project-rag /home/appuser/.local/share/project-rag
COPY --from=rag /home/raguser/.cache/project-rag       /home/appuser/.cache/project-rag

# Embedding model - relocated from /home/raguser/models to /opt/models
COPY --from=rag /home/raguser/models /opt/models

# jEAP source, read via jeap.mcp.knowledge.overview-docs-location
COPY --from=rag /jeap/src /jeap/src

# Prebuilt LanceDB index - relocated so the data path is independent of the runtime user
COPY --from=rag /home/raguser/.local/share/project-rag/lancedb /opt/jeap-rag/lancedb

# Ensure appuser owns all files it needs to read at runtime. Switch to root first - chown
# requires root regardless of the base image's USER directive.
USER root
RUN chown -R appuser:appuser \
        /home/appuser/.local/share/project-rag \
        /home/appuser/.cache/project-rag \
        /opt/jeap-rag/lancedb
USER appuser

ENV PROJECT_RAG_MODEL_PATH=/opt/models/all-MiniLM-L6-v2 \
    PROJECT_RAG_LANCEDB_PATH=/opt/jeap-rag/lancedb \
    SPRING_AI_MCP_CLIENT_ENABLED=true

# This instance repo's own build output (its own application.yml supplies the required
# properties listed in docs/configuration.md, e.g. jeap.mcp.knowledge.overview-docs-location
# and jeap.security.oauth2.resourceserver.system-name).
COPY target/*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
```

## Platform-specific — decide per instance

- **Base runtime image.** Needs a JDK 25 runtime; otherwise the specific base image is each
  instance's own choice.
- **Arbitrary-UID safety.** If the target schedules containers under a randomly-assigned UID (not
  a fixed one known in advance), don't rely on a named user owning things - make the preindexed
  content world-readable instead, and only grant group-write on the specific paths that genuinely
  need it at startup (e.g. a mounted truststore).
- **Entrypoint.** Plain `["java", "-jar", "app.jar"]` is enough if there's nothing else to wire in.
  A platform that needs mounted certs imported into the JVM truststore, external config layered
  in, or proxy settings applied at startup will need its own entrypoint script instead.
- **Secrets and per-instance config.** How these reach the running container (mounted files, a
  cloud config/secrets service, environment injection, ...) is each instance's own decision.
- **TLS.** Whether anything is needed at the application level, or the platform's own routing/
  ingress layer already handles it, is each instance's own decision.
- **Separate management/metrics port**, if the platform's probes or scraping expect one - see
  [Configuration](configuration.md#managementactuator-port) for the property this requires.
