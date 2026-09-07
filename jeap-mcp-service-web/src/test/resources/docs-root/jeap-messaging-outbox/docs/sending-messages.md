# Sending messages with the transactional outbox (local sample)

> Local development fixture — see the repository [README](../README.md) for why this
> file exists. The authoritative documentation ships with the deployed image under
> `/jeap/src`.

The transactional outbox stores an outgoing message in the same database transaction
as the business change that produced it, then relays it to Kafka asynchronously. This
yields at-least-once delivery without a distributed transaction spanning the database
and the broker.

## Steps

1. Add the outbox starter to your service.
2. Publish through the outbox API instead of the raw Kafka template.
3. The relay polls unsent rows and forwards them to the broker.

## Related

- Repository overview: [../README.md](../README.md)
