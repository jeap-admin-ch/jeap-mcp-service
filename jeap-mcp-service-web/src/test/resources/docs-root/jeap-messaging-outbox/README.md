# jeap-messaging-outbox (local sample)

> Local development fixture for the jEAP MCP server. This is **not** the published
> `jeap-messaging-outbox` documentation — it exists only so that `jeap_get_document`
> and `jeap_find_in_documentation` return real file content when the server runs on the
> host via the `local` profile, where the container path `/jeap/src` is not available.
> The authoritative sources ship inside the deployed image (see `Dockerfile`).

The transactional outbox reliably publishes domain events together with the local
database transaction that produced them.

See [docs/sending-messages.md](docs/sending-messages.md).
