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
const { requestId, uploadUrl } = await client.createValidation({
  filename: 'march-invoice.pdf',
  contentType: 'application/pdf',
  idempotencyKey: 'order-4711',
});
await client.uploadDocument(requestId, { filename, contentType, content });
const finished = await client.waitForCompletion(requestId, { timeoutMs: 30_000 });
```

Declaring the document at create time is optional. When you do, the upload may omit
`Content-Disposition`, and bytes whose type contradicts the declaration are refused.

`createClient(options)` is available if you prefer a factory to `new DocValidateClient(options)`.

## Retries follow the service's contract

The SDK retries on network failures and on 408, 429, 500, 502, 503 and 504 -
three attempts by default, exponential backoff with full jitter, honouring
`Retry-After` when the service sends one.

What it will not do is retry a request the API has not promised is safe to
replay:

| Call | Retried | Why |
|---|---|---|
| `getValidation` | yes | Reads nothing that changes |
| `uploadDocument` | yes | Identical bytes are answered `200` with no state change |
| `createValidation({ idempotencyKey })` | yes | The key returns the same request instead of a second one |
| `createValidation()` | **no** | Without a key, a replay creates a second validation |

That last row is the whole reason `Idempotency-Key` exists. Pass one whenever a
duplicate would matter to you.

## Errors

Every failure is a `DocValidateError` carrying `status`, `code` and the parsed
problem body. The subclass is chosen from the service's `code`, not from the
status, because `409` means three different things:

| Code | Error |
|---|---|
| `VALIDATION_NOT_FOUND` | `ValidationNotFoundError` |
| `CONTENT_MISMATCH` | `ContentMismatchError` |
| `DECLARED_TYPE_MISMATCH` | `DeclaredTypeMismatchError` |
| `REQUEST_EXPIRED` | `RequestExpiredError` |
| `INVALID_STATE_TRANSITION` | `InvalidStateTransitionError` |
| `INVALID_REQUEST`, `PAYLOAD_TOO_LARGE`, `LENGTH_REQUIRED` | `InvalidRequestError` |
| `STORAGE_FAILURE`, `INTERNAL_ERROR` | `ServiceError` |
| - | `NetworkError` when the request never got an answer |

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
  timeoutMs: 30_000,                                  // per attempt
  retry: { attempts: 3, baseDelayMs: 200, maxDelayMs: 5_000 },
  fetch: myInstrumentedFetch,                         // tracing, auth headers, tests
});
```

## Development

```bash
npm install
npm run verify   # typecheck, test, build, ESM+CJS smoke, attw, publint
npm run example  # happy path against a service on localhost:8080
```

Built with tsup rather than Vite: this is a library with no assets and no dev server,
and tsup wraps the same esbuild pipeline while emitting both `.d.ts` and `.d.cts`
without extra plugins. `attw` and `publint` are what actually prove the output, and
both run in `verify` and in CI.
