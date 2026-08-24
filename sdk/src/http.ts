import { DocValidateError, NetworkError, errorFor } from './errors.js';
import type { ProblemDetail } from './types.js';

export type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

export interface RetryOptions {
  /** Total attempts, including the first. 1 disables retrying. */
  attempts: number;
  /** Delay before the second attempt; doubled each time, with full jitter. */
  baseDelayMs: number;
  maxDelayMs: number;
}

export interface HttpOptions {
  baseUrl: string;
  fetch: FetchLike;
  timeoutMs: number;
  retry: RetryOptions;
  /** Injectable so tests do not spend real time asleep. */
  sleep: (ms: number) => Promise<void>;
  random: () => number;
}

interface RequestSpec {
  method: 'GET' | 'POST' | 'PUT';
  path: string;
  headers?: Record<string, string>;
  body?: BodyInit;
  /**
   * Whether replaying this request is safe. Not a guess about the verb: it is the
   * service's contract. A keyed POST returns the same request rather than minting a
   * second one, and an upload of identical bytes is answered 200 with no state change.
   */
  idempotent: boolean;
  signal?: AbortSignal | undefined;
}

const RETRYABLE_STATUSES = new Set([408, 429, 500, 502, 503, 504]);

export class HttpClient {
  constructor(private readonly options: HttpOptions) {}

  async send<T>(spec: RequestSpec): Promise<T> {
    const attempts = spec.idempotent ? Math.max(1, this.options.retry.attempts) : 1;
    let lastError: DocValidateError | undefined;

    for (let attempt = 1; attempt <= attempts; attempt++) {
      let response: Response;
      try {
        response = await this.attempt(spec);
      } catch (cause) {
        lastError = asNetworkError(cause);
        if (attempt === attempts) break;
        await this.backoff(attempt, null);
        continue;
      }

      if (response.ok) {
        return (await readJson(response)) as T;
      }

      const problem = (await readJson(response)) as ProblemDetail | undefined;
      lastError = errorFor(response.status, problem);

      if (attempt === attempts || !RETRYABLE_STATUSES.has(response.status)) {
        break;
      }
      await this.backoff(attempt, response.headers.get('retry-after'));
    }

    throw lastError ?? new DocValidateError('Request failed');
  }

  private async attempt(spec: RequestSpec): Promise<Response> {
    const timeout = AbortSignal.timeout(this.options.timeoutMs);
    const signal = spec.signal === undefined ? timeout : AbortSignal.any([timeout, spec.signal]);

    return this.options.fetch(`${this.options.baseUrl}${spec.path}`, {
      method: spec.method,
      headers: { accept: 'application/json, application/problem+json', ...spec.headers },
      ...(spec.body === undefined ? {} : { body: spec.body }),
      signal,
    });
  }

  private async backoff(attempt: number, retryAfter: string | null): Promise<void> {
    const { baseDelayMs, maxDelayMs } = this.options.retry;
    const server = retryAfter === null ? NaN : Number(retryAfter) * 1000;
    // Full jitter: retries from many clients spread out instead of arriving together.
    const backoff = Math.min(maxDelayMs, baseDelayMs * 2 ** (attempt - 1)) * this.options.random();
    await this.options.sleep(Number.isFinite(server) ? Math.min(server, maxDelayMs) : backoff);
  }
}

async function readJson(response: Response): Promise<unknown> {
  const text = await response.text();
  if (text.length === 0) return undefined;
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

function asNetworkError(cause: unknown): NetworkError {
  const reason = cause instanceof Error ? cause.message : String(cause);
  return new NetworkError(`The request did not complete: ${reason}`, { cause });
}
