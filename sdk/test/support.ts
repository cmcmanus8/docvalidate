import type { FetchLike } from '../src/http.js';

export interface RecordedRequest {
  url: string;
  method: string;
  headers: Record<string, string>;
  body: string | undefined;
}

export interface StubResponse {
  status: number;
  body?: unknown;
  headers?: Record<string, string>;
}

/** A queue of canned responses plus a log of what was actually sent. */
export function stubFetch(responses: (StubResponse | Error)[]): {
  fetch: FetchLike;
  calls: RecordedRequest[];
} {
  const calls: RecordedRequest[] = [];
  const queue = [...responses];

  const fetch: FetchLike = async (url, init) => {
    calls.push({
      url,
      method: init?.method ?? 'GET',
      headers: normalise(init?.headers),
      body: bodyOf(init?.body),
    });

    const next = queue.length > 1 ? queue.shift()! : queue[0];
    if (next === undefined) throw new Error(`No stubbed response for ${url}`);
    if (next instanceof Error) throw next;

    return new Response(next.body === undefined ? null : JSON.stringify(next.body), {
      status: next.status,
      headers: { 'content-type': 'application/json', ...next.headers },
    });
  };

  return { fetch, calls };
}

function normalise(headers: HeadersInit | undefined): Record<string, string> {
  const out: Record<string, string> = {};
  if (headers === undefined) return out;
  for (const [key, value] of Object.entries(headers as Record<string, string>)) {
    out[key.toLowerCase()] = value;
  }
  return out;
}

function bodyOf(body: BodyInit | null | undefined): string | undefined {
  if (body === undefined || body === null) return undefined;
  if (typeof body === 'string') return body;
  if (body instanceof Uint8Array) return new TextDecoder().decode(body);
  if (body instanceof ArrayBuffer) return new TextDecoder().decode(body);
  return undefined;
}

export const validation = (status: string, extra: Record<string, unknown> = {}) => ({
  requestId: '11111111-2222-3333-4444-555555555555',
  status,
  createdAt: '2026-08-25T09:00:00Z',
  updatedAt: '2026-08-25T09:00:01Z',
  expiresAt: '2026-08-25T09:15:00Z',
  ...extra,
});
