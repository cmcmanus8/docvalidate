import { describe, expect, it } from 'vitest';
import { DocValidateClient, createClient } from '../src/client.js';
import {
  ContentMismatchError,
  DeclaredTypeMismatchError,
  InvalidRequestError,
  NetworkError,
  ServiceError,
  ValidationNotFoundError,
  UnknownEndpointError,
  ValidationTimeoutError,
} from '../src/errors.js';
import { stubFetch, validation, type StubResponse } from './support.js';

const REQUEST_ID = '11111111-2222-3333-4444-555555555555';

function client(responses: (StubResponse | Error)[], overrides = {}) {
  const { fetch, calls } = stubFetch(responses);
  return {
    calls,
    client: new DocValidateClient({
      baseUrl: 'http://localhost:8080/',
      fetch,
      // No real waiting, and no jitter, so timings are assertions rather than hopes.
      sleep: async () => {},
      random: () => 1,
      ...overrides,
    }),
  };
}

describe('createValidation', () => {
  it('posts to the versioned path and returns the upload url', async () => {
    const created = { requestId: REQUEST_ID, uploadUrl: 'http://localhost:8080/x', status: 'PENDING_UPLOAD', expiresAt: 'z' };
    const { client: sdk, calls } = client([{ status: 201, body: created }]);

    await expect(sdk.createValidation()).resolves.toEqual(created);
    expect(calls[0]?.url).toBe('http://localhost:8080/api/v1/validations');
    expect(calls[0]?.method).toBe('POST');
    expect(calls[0]?.headers['idempotency-key']).toBeUndefined();
  });

  it('sends the idempotency key when one is given', async () => {
    const { client: sdk, calls } = client([{ status: 201, body: {} }]);

    await sdk.createValidation({ idempotencyKey: 'key-1' });

    expect(calls[0]?.headers['idempotency-key']).toBe('key-1');
    expect(calls[0]?.body).toBeUndefined();
  });

  it('does not retry an unkeyed create, because a replay would mint a second request', async () => {
    const { client: sdk, calls } = client([{ status: 503, body: { code: 'INTERNAL_ERROR' } }]);

    await expect(sdk.createValidation()).rejects.toBeInstanceOf(ServiceError);
    expect(calls).toHaveLength(1);
  });

  it('retries a keyed create, because the key makes the replay safe', async () => {
    const { client: sdk, calls } = client([
      { status: 503, body: { code: 'INTERNAL_ERROR' } },
      { status: 200, body: { requestId: REQUEST_ID } },
    ]);

    await expect(sdk.createValidation({ idempotencyKey: 'key-1' })).resolves.toMatchObject({ requestId: REQUEST_ID });
    expect(calls).toHaveLength(2);
  });
});

describe('uploadDocument', () => {
  it('sends the bytes with the headers the service reads', async () => {
    const { client: sdk, calls } = client([{ status: 202, body: validation('QUEUED') }]);

    await sdk.uploadDocument(REQUEST_ID, {
      filename: 'march invoice.pdf',
      contentType: 'application/pdf',
      content: 'hello',
    });

    const call = calls[0]!;
    expect(call.method).toBe('PUT');
    expect(call.url).toBe(`http://localhost:8080/api/v1/validations/${REQUEST_ID}/content`);
    expect(call.headers['content-type']).toBe('application/pdf');
    expect(call.headers['content-disposition'])
      .toBe('attachment; filename="march invoice.pdf"; filename*=UTF-8\'\'march%20invoice.pdf');
    expect(call.headers['content-length']).toBe('5');
    expect(call.body).toBe('hello');
  });

  it('retries after a network failure, which the digest rules make safe', async () => {
    const { client: sdk, calls } = client([
      new Error('socket hang up'),
      { status: 202, body: validation('QUEUED') },
    ]);

    await expect(sdk.uploadDocument(REQUEST_ID, { filename: 'a.pdf', contentType: 'application/pdf', content: 'x' }))
      .resolves.toMatchObject({ status: 'QUEUED' });
    expect(calls).toHaveLength(2);
  });

  it('gives up as a NetworkError once the attempts are spent', async () => {
    const { client: sdk, calls } = client([new Error('socket hang up')]);

    await expect(sdk.uploadDocument(REQUEST_ID, { filename: 'a.pdf', contentType: 'application/pdf', content: 'x' }))
      .rejects.toBeInstanceOf(NetworkError);
    expect(calls).toHaveLength(3);
  });

  it('surfaces a digest conflict as ContentMismatchError, not a bare 409', async () => {
    const problem = { status: 409, code: 'CONTENT_MISMATCH', detail: 'already holds different content', requestId: REQUEST_ID };
    const { client: sdk } = client([{ status: 409, body: problem }]);

    await expect(sdk.uploadDocument(REQUEST_ID, { filename: 'a.pdf', contentType: 'application/pdf', content: 'x' }))
      .rejects.toMatchObject({
        constructor: ContentMismatchError,
        code: 'CONTENT_MISMATCH',
        requestId: REQUEST_ID,
        status: 409,
      });
  });
});

describe('errors', () => {
  it('maps an unknown request to ValidationNotFoundError', async () => {
    const { client: sdk } = client([{ status: 404, body: { code: 'VALIDATION_NOT_FOUND', detail: 'nope' } }]);

    await expect(sdk.getValidation(REQUEST_ID)).rejects.toBeInstanceOf(ValidationNotFoundError);
  });

  it('falls back on status when the body carries no code', async () => {
    const { client: sdk } = client([{ status: 400, body: {} }]);

    await expect(sdk.getValidation(REQUEST_ID)).rejects.toBeInstanceOf(InvalidRequestError);
  });

  it('does not retry a 4xx', async () => {
    const { client: sdk, calls } = client([{ status: 404, body: { code: 'VALIDATION_NOT_FOUND' } }]);

    await expect(sdk.getValidation(REQUEST_ID)).rejects.toBeInstanceOf(ValidationNotFoundError);
    expect(calls).toHaveLength(1);
  });
});

describe('404s', () => {
  it('separates a missing request from a missing route', async () => {
    const missingRequest = client([{ status: 404, body: { code: 'VALIDATION_NOT_FOUND' } }]);
    await expect(missingRequest.client.getValidation(REQUEST_ID))
      .rejects.toBeInstanceOf(ValidationNotFoundError);

    const missingRoute = client([{ status: 404, body: { code: 'RESOURCE_NOT_FOUND' } }]);
    await expect(missingRoute.client.getValidation(REQUEST_ID))
      .rejects.toBeInstanceOf(UnknownEndpointError);
  });
});

describe('waitForCompletion', () => {
  it('polls until the status is terminal', async () => {
    const { client: sdk, calls } = client([
      { status: 200, body: validation('QUEUED') },
      { status: 200, body: validation('PROCESSING') },
      { status: 200, body: validation('COMPLETED', { result: { verdict: 'PASS' } }) },
    ]);

    const result = await sdk.waitForCompletion(REQUEST_ID, { pollIntervalMs: 1 });

    expect(result.status).toBe('COMPLETED');
    expect(calls).toHaveLength(3);
  });

  it('treats FAILED as an answer rather than an error', async () => {
    const { client: sdk } = client([
      { status: 200, body: validation('FAILED', { result: { verdict: 'FAIL', reason: 'EMPTY_DOCUMENT' } }) },
    ]);

    const result = await sdk.waitForCompletion(REQUEST_ID);

    expect(result.status).toBe('FAILED');
    expect(result.result?.reason).toBe('EMPTY_DOCUMENT');
  });

  it('throws with the last state it saw when the deadline passes', async () => {
    const { client: sdk } = client([{ status: 200, body: validation('PROCESSING') }]);

    await expect(sdk.waitForCompletion(REQUEST_ID, { timeoutMs: -1 }))
      .rejects.toMatchObject({ constructor: ValidationTimeoutError, lastKnown: { status: 'PROCESSING' } });
  });
});

describe('validate', () => {
  it('creates, uploads and waits', async () => {
    const { client: sdk, calls } = client([
      { status: 201, body: { requestId: REQUEST_ID, uploadUrl: 'u', status: 'PENDING_UPLOAD', expiresAt: 'z' } },
      { status: 202, body: validation('QUEUED') },
      { status: 200, body: validation('COMPLETED', { result: { verdict: 'PASS' } }) },
    ]);

    const result = await sdk.validate({ filename: 'invoice.pdf', contentType: 'application/pdf', content: 'hello' });

    expect(result.status).toBe('COMPLETED');
    expect(calls.map((c) => c.method)).toEqual(['POST', 'PUT', 'GET']);
  });
});

describe('declaring the document up front', () => {
  it('sends filename and contentType as a JSON body', async () => {
    const { client: sdk, calls } = client([{ status: 201, body: {} }]);

    await sdk.createValidation({ filename: 'invoice.pdf', contentType: 'application/pdf' });

    expect(calls[0]?.headers['content-type']).toBe('application/json');
    expect(JSON.parse(calls[0]?.body ?? '{}')).toEqual({
      filename: 'invoice.pdf',
      contentType: 'application/pdf',
    });
  });

  it('declares the document when validate() creates the request', async () => {
    const { client: sdk, calls } = client([
      { status: 201, body: { requestId: REQUEST_ID, uploadUrl: 'u', status: 'PENDING_UPLOAD', expiresAt: 'z' } },
      { status: 202, body: validation('QUEUED') },
      { status: 200, body: validation('COMPLETED', { result: { verdict: 'PASS' } }) },
    ]);

    await sdk.validate({ filename: 'invoice.pdf', contentType: 'application/pdf', content: 'x' });

    expect(JSON.parse(calls[0]?.body ?? '{}')).toMatchObject({ filename: 'invoice.pdf' });
  });

  it('maps a contradicted declaration to DeclaredTypeMismatchError', async () => {
    const { client: sdk } = client([{ status: 409, body: { code: 'DECLARED_TYPE_MISMATCH', detail: 'nope' } }]);

    await expect(sdk.getValidation(REQUEST_ID)).rejects.toBeInstanceOf(DeclaredTypeMismatchError);
  });
});

describe('validate replaying a key', () => {
  it('skips the upload when the key replayed into a request that already has one', async () => {
    const { client: sdk, calls } = client([
      { status: 200, body: { requestId: REQUEST_ID, uploadUrl: 'u', status: 'COMPLETED', expiresAt: 'z' } },
      { status: 200, body: validation('COMPLETED', { result: { verdict: 'PASS' } }) },
    ]);

    const result = await sdk.validate(
      { filename: 'invoice.pdf', contentType: 'application/pdf', content: 'x' },
      { idempotencyKey: 'already-done' },
    );

    expect(result.status).toBe('COMPLETED');
    expect(calls.map((c) => c.method)).toEqual(['POST', 'GET']);
  });
});

describe('Retry-After', () => {
  it('waits as long as the service asks, past the ordinary backoff ceiling', async () => {
    const slept: number[] = [];
    const { fetch } = stubFetch([
      { status: 503, body: { code: 'INTERNAL_ERROR' }, headers: { 'retry-after': '30' } },
      { status: 200, body: validation('COMPLETED') },
    ]);
    const sdk = new DocValidateClient({
      baseUrl: 'http://localhost:8080',
      fetch,
      sleep: async (ms) => { slept.push(ms); },
      random: () => 1,
    });

    await sdk.getValidation(REQUEST_ID);

    expect(slept).toEqual([30_000]);
  });

  it('understands the HTTP-date form that proxies send', async () => {
    const slept: number[] = [];
    const when = new Date(Date.now() + 20_000).toUTCString();
    const { fetch } = stubFetch([
      { status: 503, body: {}, headers: { 'retry-after': when } },
      { status: 200, body: validation('COMPLETED') },
    ]);
    const sdk = new DocValidateClient({
      baseUrl: 'http://localhost:8080',
      fetch,
      sleep: async (ms) => { slept.push(ms); },
    });

    await sdk.getValidation(REQUEST_ID);

    expect(slept[0]).toBeGreaterThan(15_000);
    expect(slept[0]).toBeLessThanOrEqual(20_000);
  });

  it('will not be parked indefinitely by a hostile Retry-After', async () => {
    const slept: number[] = [];
    const { fetch } = stubFetch([
      { status: 429, body: {}, headers: { 'retry-after': '86400' } },
      { status: 200, body: validation('COMPLETED') },
    ]);
    const sdk = new DocValidateClient({
      baseUrl: 'http://localhost:8080',
      fetch,
      retry: { maxRetryAfterMs: 10_000 },
      sleep: async (ms) => { slept.push(ms); },
    });

    await sdk.getValidation(REQUEST_ID);

    expect(slept).toEqual([10_000]);
  });
});

describe('hostile inputs', () => {
  it('encodes a non-ASCII filename instead of throwing inside fetch', async () => {
    const { client: sdk, calls } = client([{ status: 202, body: validation('QUEUED') }]);

    await sdk.uploadDocument(REQUEST_ID, {
      filename: 'facturé-日本.pdf',
      contentType: 'application/pdf',
      content: 'x',
    });

    const disposition = calls[0]?.headers['content-disposition'] ?? '';
    // Every byte has to be Latin-1 representable or fetch rejects the header outright.
    expect(() => new TextEncoder().encode(disposition).every((b) => b < 256)).not.toThrow();
    expect(disposition).toContain("filename*=UTF-8''");
    expect(disposition).toMatch(/filename="[\x20-\x7E]+"/);
  });

  it('refuses to hand back an empty body typed as a Validation', async () => {
    const { client: sdk } = client([{ status: 200 }]);

    await expect(sdk.getValidation(REQUEST_ID)).rejects.toBeInstanceOf(ServiceError);
  });

  it('refuses to hand back an unparseable body', async () => {
    const { fetch } = stubFetch([{ status: 200, body: undefined }]);
    const sdk = new DocValidateClient({ baseUrl: 'http://localhost:8080', fetch, sleep: async () => {} });

    await expect(sdk.getValidation(REQUEST_ID)).rejects.toBeInstanceOf(ServiceError);
  });
});

describe('createClient', () => {
  it('builds the same client as the constructor', async () => {
    const { fetch } = stubFetch([{ status: 200, body: validation('COMPLETED') }]);
    const sdk = createClient({ baseUrl: 'http://localhost:8080', fetch });

    await expect(sdk.getValidation(REQUEST_ID)).resolves.toMatchObject({ status: 'COMPLETED' });
  });
});

describe('options', () => {
  it('sends the configured headers on every request, and lets per-call ones win', async () => {
    const { fetch, calls } = stubFetch([{ status: 200, body: validation('COMPLETED') }]);
    const sdk = new DocValidateClient({
      baseUrl: 'http://localhost:8080',
      headers: { 'x-api-key': 'secret', accept: 'text/plain' },
      fetch,
    });

    await sdk.getValidation(REQUEST_ID);

    expect(calls[0]?.headers['x-api-key']).toBe('secret');
    expect(calls[0]?.headers['accept']).toBe('text/plain');
  });

  it('accepts a Blob as document content', async () => {
    const { client: sdk, calls } = client([{ status: 202, body: validation('QUEUED') }]);

    await sdk.uploadDocument(REQUEST_ID, {
      filename: 'a.pdf',
      contentType: 'application/pdf',
      content: new Blob(['blob bytes']),
    });

    expect(calls[0]?.body).toBe('blob bytes');
    expect(calls[0]?.headers['content-length']).toBe('10');
  });

  it('stops polling when the caller aborts', async () => {
    const controller = new AbortController();
    const { fetch } = stubFetch([new DOMException('This operation was aborted', 'AbortError')]);
    const sdk = new DocValidateClient({ baseUrl: 'http://localhost:8080', fetch, sleep: async () => {} });
    controller.abort();

    await expect(sdk.getValidation(REQUEST_ID, controller.signal)).rejects.toBeInstanceOf(NetworkError);
  });
});
