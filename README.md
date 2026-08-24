# docvalidate

An asynchronous document-validation slice: a Spring Boot service that accepts a
document and judges it off the request thread, and a TypeScript SDK that talks to
it. Java 21, Postgres, Kafka, and a client package a consumer would not resent.

The interesting part is not the validation - that is a deterministic stub. It is
the lifecycle around it: accepting work, finishing it later, and being honest
about what happens when a step fails in between.

## Run it

Two containers and a Gradle task:

```bash
docker compose up -d                  # Postgres on 5433, Kafka on 9092
cd service && ./gradlew bootRun
```

Postgres maps to **5433**, not 5432, because a developer machine very often
already has one. Override with `POSTGRES_PORT` if you would rather it did not.

Then, from the repo root:

```bash
./scripts/demo.sh
```

That walks the whole path with `curl`: create, replay the idempotency key, upload,
re-upload the same bytes, try to swap the document, and poll to a verdict. It is
the fastest way to see what the service actually promises.

Without a broker:

```bash
docker compose up -d postgres
cd service && SPRING_APPLICATION_JSON='{"docvalidate":{"messaging":"local"}}' ./gradlew bootRun
```

Same behaviour, in-memory publisher, no Kafka. Which adapter is live is a
property, not a profile - see [Trade-offs](#trade-offs).

## Test it

```bash
cd service && ./gradlew test     # 42 tests: domain, WebMvc slice, Postgres, one Kafka
cd sdk && npm install && npm run verify
```

`npm run verify` is typecheck, tests, build, an ESM and a CJS smoke import,
`attw` and `publint`. The service tests use Testcontainers, so they need a Docker
daemon; the first run pulls Postgres and Kafka images.

## Use the SDK

```ts
import { DocValidateClient } from '@docvalidate/sdk';

const client = new DocValidateClient({ baseUrl: 'http://localhost:8080' });

const result = await client.validate({
  filename: 'march-invoice.pdf',
  contentType: 'application/pdf',
  content: await readFile('march-invoice.pdf'),
});

console.log(result.status, result.result?.verdict, result.result?.extractedFields);
```

Runnable version, against a live service: `cd sdk && npm run build && npm run example`.
Full surface, error types and retry rules: [sdk/README.md](sdk/README.md).

## Architecture

```
Client ──POST /validations──▶ API ──▶ Postgres            (PENDING_UPLOAD)
Client ──PUT  /content─────▶ API ──▶ filesystem + Postgres (QUEUED)
                                └──▶ Kafka        (published AFTER_COMMIT)
                                          │
                                     Worker claims QUEUED ─▶ PROCESSING ─▶ COMPLETED | FAILED
Client ──GET  /validations/{id}──▶ API ──▶ status, and the result once there is one
```

| Path | Module | What lives there |
|---|---|---|
| `service/` | `domain` | The `ValidationRequest` aggregate and its status machine |
| | `application` | Use cases: create, upload, expiry sweep |
| | `api` | Controllers, problem responses, the upload size filter |
| | `persistence` | Repositories and Liquibase changesets |
| | `messaging` | `JobPublisher`/`JobConsumer` ports, Kafka and in-memory adapters |
| | `processing` | The worker and the validation stub |
| `sdk/` | | The TypeScript client |

The status machine, the idempotency rules, the messaging ports and the failure
modes are written up properly in [docs/architecture.md](docs/architecture.md),
with a sequence diagram. That document is the one to read before the code.

## Trade-offs

**Synchronous accept, asynchronous finish.** The upload returns `202` as soon as
the bytes are durable; nothing waits for a verdict. The cost is that a client has
to poll, which is why `waitForCompletion` exists in the SDK rather than being
left as an exercise. The processing stub sleeps for a second on purpose, so that
asynchrony is visible rather than theoretical.

**Publishing after commit, not inside the transaction.** Publishing inside it
would let a job reach the broker for a write that then rolled back, and the
consumer would chase a `requestId` that does not exist. After it, an unreachable
broker leaves the row `QUEUED` with no message sent: the work is stranded rather
than phantom. That is the better failure, but it is still a gap, and a
transactional outbox is the fix. Named, not hidden.

**At-least-once delivery with an idempotent claim.** The worker takes a job with
a conditional `UPDATE ... WHERE status = 'QUEUED'`. Zero rows means someone else
has it, or the message is a redelivery, and either way there is nothing to do.
That single statement is what makes exactly-once machinery unnecessary. There is
a test that delivers the same job twice and asserts one result row.

**Storage keeps a key, never the bytes.** Documents go to the filesystem under
`.data/{requestId}`; the database stores the key. That is what makes an S3
adapter a drop-in, and it keeps the happy path free of an AWS account.

**Idempotency is a documented contract, not a hope.** `Idempotency-Key` on create
returns the same request rather than minting a second one. A re-upload of
identical bytes is accepted with no state change; a re-upload of *different*
bytes is refused, because silently swapping the document under a validation that
has already been judged is worse than an error. The SDK's retry policy is derived
from those rules - it will retry a keyed create and an upload, and never an
unkeyed create.

**The adapter is a property, not a profile.** An earlier version selected the
in-memory publisher on "not the kafka profile", which quietly made it the default
of every profile that was not called `kafka`. A deployment under `prod` would
have started happily with no broker and no durability.

**No `confirm` endpoint.** The brief marks it optional. With a service-hosted
`PUT`, the upload response is the confirmation, so implementing one would be a
no-op that exists to look complete. It earns its place the day uploads go
straight to S3.

## With another day

1. **Transactional outbox**, plus a sweeper for rows stuck in `QUEUED`. It is the
   one real hole in the current design and I would rather close it than add
   features.
2. **Retry topic and DLQ.** Today a transient fault is recorded as `FAILED`
   exactly like a permanent one, because retrying in place would block the
   partition.
3. **OpenAPI** via springdoc, which is worth roughly fifteen minutes and gives a
   reviewer something to click.
4. **An auth stub** in front of the API. The SDK already sends arbitrary headers,
   so the client side is ready for it.
5. **Presigned S3 uploads**, at which point `confirm` becomes real work.

## AI usage

[AI_USAGE.md](AI_USAGE.md) - which tools did what, two suggestions I rejected and
why, and how the dual build was actually verified.
