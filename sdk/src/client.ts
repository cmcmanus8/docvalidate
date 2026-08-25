import { HttpClient, type FetchLike, type RetryOptions } from './http.js';
import { ValidationTimeoutError } from './errors.js';
import { isTerminal, type CreateValidationResponse, type Validation } from './types.js';

export interface DocValidateClientOptions {
  /** e.g. http://localhost:8080 - the /api/v1 prefix is the SDK's business, not yours. */
  baseUrl: string;
  /** Sent on every request: an API key, a tenant id, a trace header. */
  headers?: Record<string, string>;
  /** Per-attempt timeout. Defaults to 30s. */
  timeoutMs?: number;
  retry?: Partial<RetryOptions>;
  /** Swap in for tests, or to add tracing headers in front of the real one. */
  fetch?: FetchLike;
  /** Test seam: replaces the timer the retry backoff and the poll loop wait on. */
  sleep?: (ms: number) => Promise<void>;
  /** Test seam: replaces the jitter source, so backoff delays become deterministic. */
  random?: () => number;
}

export interface DocumentInput {
  filename: string;
  contentType: string;
  content: Uint8Array | ArrayBuffer | Blob | string;
}

export interface CreateValidationInput {
  /** Declared up front so the upload can omit Content-Disposition. */
  filename?: string;
  /** Declared up front; bytes that contradict it are refused. */
  contentType?: string;
  /** Makes the create replayable - and therefore safe for the SDK to retry. */
  idempotencyKey?: string;
  signal?: AbortSignal;
}

export interface WaitOptions {
  /** Give up after this long. Defaults to 60s. */
  timeoutMs?: number;
  pollIntervalMs?: number;
  signal?: AbortSignal;
}

const DEFAULT_RETRY: RetryOptions = {
  attempts: 3,
  baseDelayMs: 200,
  maxDelayMs: 5_000,
  maxRetryAfterMs: 60_000,
};

export class DocValidateClient {
  private readonly http: HttpClient;
  private readonly sleep: (ms: number) => Promise<void>;

  constructor(options: DocValidateClientOptions) {
    const sleep = options.sleep ?? ((ms) => new Promise((resolve) => setTimeout(resolve, ms)));
    this.sleep = sleep;
    this.http = new HttpClient({
      baseUrl: options.baseUrl.replace(/\/+$/, ''),
      fetch: options.fetch ?? globalThis.fetch.bind(globalThis),
      headers: options.headers ?? {},
      timeoutMs: options.timeoutMs ?? 30_000,
      retry: { ...DEFAULT_RETRY, ...options.retry },
      sleep,
      random: options.random ?? Math.random,
    });
  }

  /**
   * @param idempotencyKey replaying the same key returns the same request rather than
   *   creating a second one - which is also what makes this call safe to retry.
   */
  async createValidation(input: CreateValidationInput = {}): Promise<CreateValidationResponse> {
    const declaration: Record<string, string> = {};
    if (input.filename !== undefined) declaration['filename'] = input.filename;
    if (input.contentType !== undefined) declaration['contentType'] = input.contentType;
    const declares = Object.keys(declaration).length > 0;

    return this.http.send<CreateValidationResponse>({
      method: 'POST',
      path: '/api/v1/validations',
      headers: {
        ...(input.idempotencyKey === undefined ? {} : { 'idempotency-key': input.idempotencyKey }),
        ...(declares ? { 'content-type': 'application/json' } : {}),
      },
      ...(declares ? { body: JSON.stringify(declaration) } : {}),
      idempotent: input.idempotencyKey !== undefined,
      signal: input.signal,
    });
  }

  async uploadDocument(requestId: string, document: DocumentInput, signal?: AbortSignal): Promise<Validation> {
    const body = await toBytes(document.content);
    return this.http.send<Validation>({
      method: 'PUT',
      path: `/api/v1/validations/${encodeURIComponent(requestId)}/content`,
      headers: {
        'content-type': document.contentType,
        'content-disposition': contentDisposition(document.filename),
        'content-length': String(body.byteLength),
      },
      body,
      // Re-uploading identical bytes is answered 200 with no state change, so a retry
      // after a timeout cannot queue the same document twice.
      idempotent: true,
      signal,
    });
  }

  async getValidation(requestId: string, signal?: AbortSignal): Promise<Validation> {
    return this.http.send<Validation>({
      method: 'GET',
      path: `/api/v1/validations/${encodeURIComponent(requestId)}`,
      idempotent: true,
      signal,
    });
  }

  /**
   * Polls until the request reaches a terminal status. Throws rather than returning a
   * half-finished validation: a caller that wanted the current state would have called
   * getValidation, and the timeout carries the last state it saw for the ones that care.
   */
  async waitForCompletion(requestId: string, options: WaitOptions = {}): Promise<Validation> {
    const timeoutMs = options.timeoutMs ?? 60_000;
    const pollIntervalMs = options.pollIntervalMs ?? 500;
    const deadline = Date.now() + timeoutMs;

    let latest = await this.getValidation(requestId, options.signal);
    while (!isTerminal(latest.status)) {
      if (Date.now() >= deadline) {
        throw new ValidationTimeoutError(
          `Request ${requestId} was still ${latest.status} after ${timeoutMs}ms`,
          latest,
        );
      }
      await this.sleep(pollIntervalMs);
      latest = await this.getValidation(requestId, options.signal);
    }
    return latest;
  }

  /** Create, upload and wait. The three-call dance most callers actually want. */
  async validate(
    document: DocumentInput,
    options: WaitOptions & { idempotencyKey?: string } = {},
  ): Promise<Validation> {
    const created = await this.createValidation({
      filename: document.filename,
      contentType: document.contentType,
      ...(options.idempotencyKey === undefined ? {} : { idempotencyKey: options.idempotencyKey }),
      ...(options.signal === undefined ? {} : { signal: options.signal }),
    });

    // An idempotency key can replay into a request that already has its document, and
    // uploading again would be answered 409 by a service that is behaving correctly.
    // Replaying the key is meant to be the safe thing to do, so honour that here.
    if (created.status === 'PENDING_UPLOAD') {
      await this.uploadDocument(created.requestId, document, options.signal);
    }
    return this.waitForCompletion(created.requestId, options);
  }
}

/** An ArrayBuffer rather than a view: a Uint8Array is not a BodyInit, and a view can
 *  cover part of a larger buffer, which would put the wrong bytes on the wire. */
async function toBytes(content: Uint8Array | ArrayBuffer | Blob | string): Promise<ArrayBuffer> {
  if (typeof content === 'string') return toBytes(new TextEncoder().encode(content));
  if (content instanceof ArrayBuffer) return content;
  if (content instanceof Blob) return content.arrayBuffer();
  return content.buffer.slice(content.byteOffset, content.byteOffset + content.byteLength) as ArrayBuffer;
}

/**
 * RFC 6266: a plain filename parameter is a ByteString, so an accented or CJK filename
 * throws inside fetch before the request is made - and, being deterministic, would then
 * be retried three times. The ASCII form is the fallback; filename* carries the truth.
 */
function contentDisposition(filename: string): string {
  const ascii = filename.replace(/[^\x20-\x7E]/g, '_').replace(/["\\]/g, '_');
  return `attachment; filename="${ascii}"; filename*=UTF-8''${encodeURIComponent(filename)}`;
}

/** Factory alternative to `new DocValidateClient(...)`, for callers who prefer it. */
export function createClient(options: DocValidateClientOptions): DocValidateClient {
  return new DocValidateClient(options);
}
