# jeap-mcp-service

`jeap-mcp-service` is a Spring Boot/jEAP service that exposes an MCP server for agentic development
of jEAP applications.

## Docs

- [Usage](docs/usage.md) — what it provides, connecting a client, example prompts
- [Configuration](docs/configuration.md) — the property reference every instance repo needs to supply
- [Deployment](docs/deployment.md) — the `Dockerfile` contract each instance repo needs to satisfy
- [Metrics](docs/metrics.md) — the Prometheus metrics reference and PromQL

## Run locally

### Prerequisites

1. Java 25
2. Docker (for running `project-rag` locally, see below)
3. Local OAuth mock server running on `http://localhost:8081/applicationplatform-oauth-mock-server`

### Configuration

`jeap-mcp-service-web` ships no `application.yml`/`application-local.yml` of its own — reusable
jEAP microservices don't bundle local-dev settings (see [Configuration](docs/configuration.md) for
why and what's a library default vs. instance-required). To run it locally, create your own
git-ignored `application-local.yml` under `jeap-mcp-service-web/src/main/resources/` (or pass
the same properties as command-line args) supplying at least the two required properties:

```yaml
jeap:
  mcp:
    knowledge:
      overview-docs-location: file:src/test/resources/jeap-docs   # sample tree also used by tests
  security:
    oauth2:
      resourceserver:
        system-name: "local-dev"
```

### Start the application

```bash
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run -pl jeap-mcp-service-web
```

The app starts on port `8080` with context path `/jeap-mcp-service`.

If you do **not** have a local `project-rag` binary/model configured, leave
`spring.ai.mcp.client.enabled` at its default (`false`) so the upstream-backed tools fail at call
time rather than at startup. The filesystem-backed surface still works without the backend
(`jeap_overview` and `jeap_get_document`). To exercise the RAG-backed tools locally, run
`project-rag` via Docker and point `spring.ai.mcp.client.stdio.connections.project-rag` at it —
see `jeap-mcp-service-web/src/test/resources/application-mcp-client-test.yml` for a working example.

Other jEAP doc corpus paths returned by `jeap_find_in_documentation` are not on disk locally
outside the sample tree above, so those hits degrade to a `pointer` rather than full `content`.

## Build and test

```bash
# Build (compile + package)
./mvnw clean install

# Unit tests
./mvnw test

# Unit + integration tests
./mvnw verify

# JUnit end-to-end test against a project-rag MCP server (bundled binary + model)
MCP_CLIENT_TEST=true ./mvnw verify -pl jeap-mcp-service-web -Dit.test=JeapRagToolsIT

# JUnit end-to-end test against a jEAP MCP server image (bundled binary, model, sources/docs)
# This repo has no Dockerfile of its own (see docs/deployment.md) - build one from an instance
# repo instead, e.g.:
#   cd ../<instance-repo> && ./mvnw package && docker build --build-arg PREINDEXED_TAG=latest -t jeap-mcp-service:local . && cd -
SERVER_IMAGE=jeap-mcp-service:local ./mvnw verify -pl jeap-mcp-service-web -Dit.test=JeapDocsIT
```

No single deployable artifact — `jeap-mcp-service-web` is a plain library jar, not directly
runnable. Each instance repo produces its own executable jar via its own `mvn package`. See
[docs/deployment.md](docs/deployment.md).

## Changes

This project is versioned using [Semantic Versioning](http://semver.org/) and all changes are documented in
[CHANGELOG.md](./CHANGELOG.md) following the format defined in [Keep a Changelog](http://keepachangelog.com/).

## Note

This repository is part the open source distribution of jEAP. See [github.com/jeap-admin-ch/jeap](https://github.com/jeap-admin-ch/jeap)
for more information.

## License

This repository is Open Source Software licensed under the [Apache License 2.0](./LICENSE).
