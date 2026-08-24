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
  sleep?: (ms: number) => Promise<void>;
  random?: () => number;
}

export interface DocumentInput {
  filename: string;
  contentType: string;
  content: Uint8Array | ArrayBuffer | Blob | string;
}

export interface WaitOptions {
  /** Give up after this long. Defaults to 60s. */
  timeoutMs?: number;
  pollIntervalMs?: number;
  signal?: AbortSignal;
}

const DEFAULT_RETRY: RetryOptions = { attempts: 3, baseDelayMs: 200, maxDelayMs: 5_000 };

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
  async createValidation(idempotencyKey?: string, signal?: AbortSignal): Promise<CreateValidationResponse> {
    return this.http.send<CreateValidationResponse>({
      method: 'POST',
      path: '/api/v1/validations',
      ...(idempotencyKey === undefined ? {} : { headers: { 'idempotency-key': idempotencyKey } }),
      idempotent: idempotencyKey !== undefined,
      signal,
    });
  }

  async uploadDocument(requestId: string, document: DocumentInput, signal?: AbortSignal): Promise<Validation> {
    const body = await toBytes(document.content);
    return this.http.send<Validation>({
      method: 'PUT',
      path: `/api/v1/validations/${encodeURIComponent(requestId)}/content`,
      headers: {
        'content-type': document.contentType,
        'content-disposition': `attachment; filename="${escapeQuotes(document.filename)}"`,
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
    const created = await this.createValidation(options.idempotencyKey, options.signal);
    await this.uploadDocument(created.requestId, document, options.signal);
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

function escapeQuotes(filename: string): string {
  return filename.replace(/["\\]/g, '_');
}
