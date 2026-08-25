/**
 * Happy path against a running service. Start it first:
 *
 *   docker compose up -d postgres kafka
 *   cd service && ./gradlew bootRun
 *
 * then, from sdk/:  npm run build && node examples/validate.ts
 */
import { readFile } from 'node:fs/promises';
import { DocValidateClient, ContentMismatchError, ValidationTimeoutError } from '../dist/index.js';

const baseUrl = process.env.DOCVALIDATE_URL ?? 'http://localhost:8080';
const client = new DocValidateClient({ baseUrl });

const content = process.argv[2] === undefined
  ? Buffer.from('invoice total: 42.00\nvat: 8.40\n')
  : await readFile(process.argv[2]);

const filename = process.argv[2] ?? 'march-invoice.pdf';

console.log(`Validating ${filename} against ${baseUrl}`);

try {
  const started = Date.now();
  const result = await client.validate(
    { filename, contentType: 'application/pdf', content },
    { idempotencyKey: `example-${filename}`, timeoutMs: 30_000 },
  );

  console.log(`  ${result.status} after ${Date.now() - started}ms`);
  console.log(`  verdict: ${result.result?.verdict ?? '-'}`);
  console.log(`  fields:  ${JSON.stringify(result.result?.fields ?? {})}`);

  // Re-running with the same key returns the same request rather than a second one.
  const again = await client.createValidation({ idempotencyKey: `example-${filename}` });
  console.log(`  replaying the idempotency key returned ${again.requestId === result.requestId ? 'the same' : 'a DIFFERENT'} request`);
} catch (e) {
  if (e instanceof ValidationTimeoutError) {
    console.error(`Gave up while the request was still ${e.lastKnown.status}`);
  } else if (e instanceof ContentMismatchError) {
    console.error('That request already holds a different document');
  } else {
    throw e;
  }
  process.exitCode = 1;
}
