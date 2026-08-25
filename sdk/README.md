# @docvalidate/sdk

TypeScript client for the DocValidate API. Node 20+, ESM and CJS, no runtime
dependencies.

```ts
import { DocValidateClient } from '@docvalidate/sdk';

const client = new DocValidateClient({ baseUrl: 'http://localhost:8080' });

const result = await client.validate({
  filename: 'march-invoice.pdf',
  contentType: 'application/pdf',
  content: await readFile('march-invoice.pdf'),
});

console.log(result.status, result.result?.verdict, result.result?.fields);
```

`validate` is the three-call sequence most callers want. The steps are available
separately when you need them:

```ts
const document = {
  filename: 'march-invoice.pdf',
  contentType: 'application/pdf',
  content: await readFile('march-invoice.pdf'),
};

const { requestId } = await client.createValidation({
  filename: document.filename,
  contentType: document.contentType,
  idempotencyKey: 'order-4711',
});
await client.uploadDocument(requestId, document);
const finished = await client.waitForCompletion(requestId, { timeoutMs: 30_000 });
```

Declaring the document at create time is optional. When you do, bytes whose type
contradicts the declaration are refused with `DeclaredTypeMismatchError`.

`createClient(options)` is available if you prefer a factory to `new DocValidateClient(options)`.

Two things the client does **not** do yet. It ignores the `uploadUrl` the service
returns and builds the upload path itself, which is correct today because that URL
points back at the same service - but the day it becomes a presigned S3 URL, the SDK
has to `PUT` there instead, and must not send your API headers to a bucket.
`waitForCompletion` also checks its deadline between polls, so it can overshoot
`timeoutMs` by one poll interval plus a request.

For manual polling, `isTerminal(status)` and `TERMINAL_STATUSES` are exported so you
do not have to hard-code which statuses stop the loop.

## Retries follow the service's contract

The SDK retries on network failures and on 408, 429, 500, 502, 503 and 504 -
three attempts by default, exponential backoff with full jitter.

A `Retry-After` header takes precedence over that backoff, in both the
delay-in-seconds and the HTTP-date form. It is followed even when it exceeds
`maxDelayMs`, because clamping a service's request for a 60-second cooldown down to
5 seconds is how you make an overloaded service worse - but it is capped at
`maxRetryAfterMs` (60s by default) so a bad header cannot park your call forever.

What it will not do is retry a request the API has not promised is safe to
replay:

| Call | Retried | Why |
|---|---|---|
| `getValidation` | yes | Reads nothing that changes |
| `uploadDocument` | yes | Identical bytes are answered `200` with no state change, in any status |
| `createValidation({ idempotencyKey })` | yes | The key returns the same request instead of a second one |
| `createValidation()` | **no** | Without a key, a replay creates a second validation |

`validate()` takes an optional `idempotencyKey` too, and the same row applies: without
one, its create step is not retried.

That last row is the whole reason `Idempotency-Key` exists. Pass one whenever a
duplicate would matter to you.

## Errors

Every failure is a `DocValidateError`. The ones that got an HTTP response carry
`status`, `code` and the parsed problem body. The subclass is chosen from the service's `code`, not from the
status, because `409` means three different things:

| Code | Error |
|---|---|
| `VALIDATION_NOT_FOUND` | `ValidationNotFoundError` |
| `CONTENT_MISMATCH` | `ContentMismatchError` |
| `DECLARED_TYPE_MISMATCH` | `DeclaredTypeMismatchError` |
| `REQUEST_EXPIRED` | `RequestExpiredError` |
| `INVALID_STATE_TRANSITION` | `InvalidStateTransitionError` |
| `INVALID_REQUEST`, `PAYLOAD_TOO_LARGE`, `LENGTH_REQUIRED`, `METHOD_NOT_ALLOWED` | `InvalidRequestError` |
| `RESOURCE_NOT_FOUND` | `UnknownEndpointError` - the *route* is missing, not your request |
| `STORAGE_FAILURE`, `INTERNAL_ERROR` | `ServiceError` |
| - | `NetworkError` when the request never got an answer |

`NetworkError` and `ValidationTimeoutError` are the two that carry no `status` or
`code`: nothing answered, so there is nothing to report. Every other error has both.

```ts
try {
  await client.uploadDocument(requestId, document);
} catch (e) {
  if (e instanceof ContentMismatchError) {
    // This request already holds a different document.
  }
}
```

`waitForCompletion` throws `ValidationTimeoutError` rather than returning a
half-finished validation, and the error carries `lastKnown` for callers that
want to see how far it got. A `FAILED` result is not an error: the service
answered, and the answer was that the document did not validate.

## Options

```ts
new DocValidateClient({
  baseUrl: 'https://docvalidate.example.com',
  headers: { 'x-api-key': '...' },                    // sent on every request
  timeoutMs: 30_000,                                  // per attempt, not per call
  retry: { attempts: 3, baseDelayMs: 200, maxDelayMs: 5_000, maxRetryAfterMs: 60_000 },
  fetch: myInstrumentedFetch,                         // tracing, auth, tests
  sleep: myTimer,                                     // test seam
  random: myJitter,                                   // test seam
});
```

`waitForCompletion` and `validate` additionally take `{ timeoutMs, pollIntervalMs,
signal }`; every call accepts an `AbortSignal`.

## Development

```bash
npm ci
npm run verify   # typecheck, test, build, ESM+CJS smoke, attw, publint
npm run example  # happy path against a service on localhost:8080
```

Built with tsup rather than Vite: this is a library with no assets and no dev server,
and tsup wraps the same esbuild pipeline while emitting both `.d.ts` and `.d.cts`
without extra plugins. `attw` and `publint` are what actually prove the output, and
both run in `verify` and in CI.
