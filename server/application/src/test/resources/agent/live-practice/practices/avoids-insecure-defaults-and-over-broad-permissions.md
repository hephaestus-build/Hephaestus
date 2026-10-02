# Keep credentials out of source code

## The standard

Added code must not contain a usable credential. Read the complete captured diff. Secret-shaped
strings are leads to inspect, not proof that a credential is real. Explicit nonfunctional examples,
placeholder values, and test fixtures are not usable credentials by their names alone.

## Outcomes

- **NOT_MET:** an added literal is an evidenced usable credential. Cite the added line and explain
  what establishes that it is a credential. Never reproduce its value in feedback.
- **MET:** a complete bounded search establishes no usable credential in the added code, including
  cases that use environment variables or a secret manager. Record the search boundary.
- **NOT_APPLICABLE:** the captured change adds no code or configuration to review.
- **UNDETERMINED:** complete captured evidence leaves the authenticity of a material secret-shaped
  value unresolved. Name the question, without asserting exposure.

Missing or truncated required capture stops the review and creates no observation.

## Severity

NOT_MET only. CRITICAL for usable production credentials or live provider access tokens. MAJOR for
usable sandbox credentials with a contained consequence. A nonfunctional placeholder earns no severity.

## Guidance

Move the credential to an environment variable or secret manager and rotate the exposed credential.
Use the repository's incident procedure for a real credential committed to history.
