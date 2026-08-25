# AI usage

## What I used, and where

**Claude Code (Opus 5)** wrote most of the code in this repository, working
phase by phase against a design I set first: the Spring scaffold and
`docker-compose.yml`, the aggregate and status machine, the API and problem
handling, the Kafka and in-memory adapters, the worker, the TypeScript SDK, and
the tests on both sides. I reviewed each phase before the next one started, and
the architecture document was written and agreed before any Java existed - so
the code was written against a contract rather than the contract being
back-filled from the code.

I also used **Claude Code's `/code-review`** as an adversarial second pass, once
after the API layer and once after the messaging layer, rather than trusting the
same model that wrote the code to also sign it off. That was the most valuable
part of the workflow: the two passes returned seventeen findings between them,
of which everything material is fixed in commits `fdd43d5` and `7911865`. The
commit messages say what was wrong and why.

What I kept for myself: the status machine and the transition table, the
idempotency rules, the decision to skip a `confirm` endpoint, and the decision
to make the messaging adapter a property rather than a profile.

## Keeping the loop cheap

A long assistant session gets expensive and, worse, gets vague - the more context it
carries, the more confidently it repeats itself. What kept this tractable:

- **One phase, one commit.** Persistence, then the API, then messaging, then the SDK.
  Each phase ended in a commit with the reasoning in the message, so the repository
  carried the state rather than the conversation having to.
- **Review as a separate pass.** The model that wrote the code is a poor judge of it,
  so `/code-review` ran against the diff as its own step. That is where the profile
  bug and the expiry bug were caught.
- **Patches, not regeneration.** Changes were applied as targeted edits rather than
  by rewriting whole files, which keeps diffs reviewable - the point is to read what
  changed, and a regenerated file makes that impossible.
- **Verification by tooling, not by asking.** `./gradlew test`, `attw`, `publint` and
  a real `curl` run against a live service decide whether something works. Two of the
  bugs in this document were found by a test failing, not by anybody reading code.

## Suggestions I rejected

**1. Selecting the in-memory publisher with `@Profile("!kafka")`.** This was
suggested - and I accepted it at first - as a safety net: exactly one publisher
bean always exists, so a missing profile can never fail startup. Review showed
it was the opposite of safe. Any profile that was not literally named `kafka`
silently got the in-memory adapter, so running under `prod` would have started
cleanly with no broker, no durability and no warning. Failing to start is a much
better outcome than running without the queue. It is now
`docvalidate.messaging=kafka|local`, an unrecognised value starts nothing, and
the in-memory adapter logs a warning on the way up.

**2. Recording the `EXPIRED` transition and then throwing to produce the 409.**
It reads correctly and it is wrong: the exception rolls back the transaction that
contained the transition, so the service reported a state change it had just
discarded and the request sat in `PENDING_UPLOAD` for good. The service now
commits the transition and returns an outcome, and the controller turns that into
the 409. Same response to the client, and the state change survives.

Two smaller ones, for flavour: an upload was being written to storage *before*
the domain checked whether the transition was legal, so a rejected upload could
overwrite bytes on disk before returning 409; and a path-traversal test asserted
that the sanitised filename must not contain `..`, which is the wrong property -
what matters is that the resolved path cannot escape the request directory.

## How I verified the dual build

Not by reading the `exports` map. The commands, from `sdk/`:

```bash
npm run build                          # tsup: ESM, CJS and .d.ts/.d.cts
node examples/dual-build-smoke.mjs     # import  { DocValidateClient } from '../dist/index.js'
node examples/dual-build-smoke.cjs     # require('../dist/index.cjs')
npx attw --pack .                      # resolution under node10, node16 CJS/ESM, bundler
npx publint                            # packaging lint
```

`npm run verify` runs all of it plus typecheck and tests, and CI runs `npm run
verify` on every push.

Two things this caught that inspection would not have:

- **publint** flagged that a single `types` condition resolves as ESM under
  `require`, so CJS consumers would only have got types via dynamic import. Fixed
  by splitting `types` per condition with a `.d.cts`.
- **`attw` 0.17 fails against npm 11** with `Cannot read properties of undefined`,
  which looks exactly like a broken package until you check the tool. Pinned to
  0.18.5, which produces the clean four-row matrix.

## What I would not let AI own here

Anything where "looks right" and "is right" only diverge under concurrency or
failure. Specifically:

- **Transaction boundaries and idempotency semantics.** Both rejected suggestions
  above are in this category, and both were confidently written. The
  idempotent-create race is now covered by a test that runs two real threads
  against a real unique index, because that is the only way to know.
- **Liquibase changesets once they have been applied anywhere.** A rewritten
  changeset fails the checksum on every environment that already ran it. New
  changeset, always - and that discipline has to be a human rule, because the
  fastest way to make a test pass is to edit the old one.
- **The public error contract.** The `ProblemCode` values and the SDK error
  classes are what a consumer writes `catch` blocks against; renaming one is a
  breaking change that no test will notice.
- **Security-adjacent input handling** - the filename sanitising, the upload size
  cap, the `Content-Length` requirement. Generated code defaults to the happy
  path, and each of these exists because something asked "what does a hostile
  client send here".
