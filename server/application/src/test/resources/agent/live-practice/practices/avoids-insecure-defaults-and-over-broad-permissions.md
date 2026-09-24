# Hardcoded Secrets

## Definition

Flag literal API keys, passwords, tokens, credentials, or other secret-shaped strings
that are checked into source code as constants.

## Detection signals

- String literals assigned to identifiers named `apiKey`, `api_key`, `password`, `secret`,
  `token`, `credential`, `dbPassword`, `accessKey`, or similar.
- Strings matching common provider prefixes: `sk-`, `ghp_`, `AKIA…`, `xoxb-`, `Bearer …`,
  Base64-encoded blocks adjacent to keywords like "secret" or "token".
- High-entropy strings stored as constants in committed code.

## Verdict rules

- **PRESENT / BAD** when any such literal is present in added (`+`) lines.
- **PRESENT / GOOD** when secrets are referenced via env vars / a config service / a vault.
- **NOT_APPLICABLE** when the diff contains no secret-shaped material at all.

## Severity

- **CRITICAL** for production credentials, real-looking provider keys, database
  passwords, or anything that resembles a live access token.
- **MAJOR** for sandbox/test secrets, sample keys clearly marked as fake, or low-risk
  internal tokens that still should not be committed.
- **MINOR** for placeholder secrets in test fixtures explicitly named as such (e.g.
  `"PLACEHOLDER"`).

## Guidance

Move the secret to an environment variable or secret manager. Rotate the exposed
credential. Update history removal (BFG / `git filter-repo`) for any real production
credential that was committed.
