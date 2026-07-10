# Contributing to MongrelDB Scala

Thanks for taking the time to help the MongrelDB Scala client. This document
describes how to propose a change and the standards that apply.

## Code of conduct

Be kind, be specific, assume good faith. Disagree about the technical details,
not the person.

## How to propose a change

The MongrelDB Scala client uses a standard **fork -> branch -> pull request**
workflow on GitHub.

1. **Fork** [`visorcraft/MongrelDB-Scala`](https://github.com/visorcraft/MongrelDB-Scala).
2. **Clone** your fork and add the upstream remote.
3. **Branch** from `master` with a descriptive, kebab-case name.
4. **Make focused commits.** One logical change per commit.
5. **Open a pull request** against `master`. Fill in: what, why, how to test,
   and risk.

## Before you push: preflight

```sh
sbt compile
sbt test
```

All steps must pass. To run the live integration suite (requires a running
`mongreldb-server`):

```sh
MONGRELDB_URL=http://127.0.0.1:8453 sbt test
```

Live tests self-skip when no server is reachable.

## What we look for in a review

- The change does one thing and does it well.
- Behavior changes ship with tests. Daemon-dependent coverage: a live test that
  skips cleanly when no server is available.
- The change keeps this repo a thin client over `mongreldb-server`. Don't
  re-implement storage, indexing, WAL, or SQL planning logic here.
- Documentation is updated alongside the code if the change affects users.
- Commits have clear messages.

## Coding standards

### Scala

- **Version.** Scala 3.3+ on Java 11+. Don't drop the minimum casually.
- **Dependencies.** No external runtime dependencies — only the Java/Scala
  standard library. New dependencies must be MIT or Apache-2.0 licensed and
  justified.
- **Errors.** Throw a typed exception hierarchy (`MongrelDBException` base,
  `AuthException`, `NotFoundException`, `ConflictException`, `QueryException`)
  carrying the HTTP status and decoded server envelope.
- **Naming.** Idiomatic Scala: `PascalCase` types, `camelCase` methods.

### Commit messages

- Subject line: imperative mood, <= 72 characters, no trailing period.
- Body: wrap at 72 characters. Explain *why*, not *what*.
- Reference issues with `Fixes #123` / `Refs #123` when applicable.
- **Never** add AI/assistant attribution.

## Issue reports

A useful bug report includes the client version, Java/Scala version, OS, the
`mongreldb-server` version, the exact reproduction steps, and expected vs
actual results.

## Security

If you find a vulnerability, **do not** open a public GitHub issue. Report it
privately through GitHub's private vulnerability reporting. See
[`SECURITY.md`](SECURITY.md).

## Licensing

The MongrelDB Scala client is dual-licensed under MIT OR Apache-2.0. By
contributing, you agree that your changes are made available under the same
license. New third-party dependencies must be MIT or Apache-2.0 licensed.
