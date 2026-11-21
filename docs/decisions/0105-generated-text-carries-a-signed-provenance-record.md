# ADR 0105: Generated text carries a signed provenance record

**Status:** Accepted
**Date:** 2026-11-15
**Authors:** Felix T.J. Dietrich

## Context

EU AI Act Art. 50(2) requires machine-readable marking of AI-generated text, so that the text is detectable as artificially generated.
Hephaestus posts generated feedback where anyone with access can copy it: GitHub and GitLab comments, the practice page, the conversation, Slack and exports.
A visible footer tells a reader, but a tool cannot check it, and anyone can copy or forge it.
The marking must stay on and give an operator no way to turn it off.
It must also hold for an operator who uses a hosted model API, which offers no control over token sampling.

## Decision drivers

- A third party can check that the instance signed a mark without asking the instance.
- The mark names no person and no workspace, because it travels with the text.
- One mechanism for every destination. Each destination only chooses where the mark goes.
- No new secret store. The system key of [ADR 0104](0104-the-system-key-and-the-signing-key-rotate-without-a-stop.md) seals the signing key.

## Considered options

1. **A signed record per text: a compact JWS (RFC 7515) with EdDSA over Ed25519 (RFC 8037), and public keys as a JWK set (chosen).** Any JOSE library verifies it. Each destination carries the same string.
2. **An unsigned flag, such as `generator=ai` in an HTML comment.** It is cheap, but anyone can forge or copy it to text that a person wrote.
3. **A statistical watermark in the model's sampling.** Hosted APIs do not expose sampling. Hephaestus escapes, splits and cuts the text after generation, and most feedback is too short for a watermark to hold.
4. **Zero-width characters in the text.** `PostedText` removes invisible characters on purpose, screen readers and copying break, and anyone can strip them.

## Decision

1. **The record.**
   - Header: `alg: EdDSA`, `kid`, `typ: hephaestus-provenance+jws`.
   - Claims: `v` (1), `iss` (the instance's web address), `id`, `gen` (`Hephaestus`), `ver` (the release), `model` (the upstream model ID, absent when unknown), `at`, `kind`, `sha256`.
   - `sha256` hashes the stored text in its canonical form (`ProvenanceText`: NFC, LF line ends, no trailing spaces, trimmed). The escaped form that a provider shows is not what it hashes: each provider escapes differently, so a hash of the posted form would tie one record to one destination.
   - It names no person and no workspace.
2. **The keys.**
   - `provenance_signing_key` holds Ed25519 keys. The private key is sealed by `SystemSecrets` and bound to its `kid`.
   - The server checks at start that every key opens, and makes the first key under a cluster lock.
   - The newest key signs. Every key stays to verify, for the life of the instance.
   - `/.well-known/hephaestus-provenance-keys.json` publishes the public keys without sign-in.
   - The re-seal pass of the system key covers the table (`SystemSecretOwner`).
3. **When it signs.** Text is signed when Hephaestus stores it in its final form: feedback when it is prepared (`FeedbackLedgerRecorder`). The record is stored on the row that holds the text, so tenancy, retention and erasure follow that row.
4. **Where it goes.**
   - On GitHub and GitLab: a hidden line `<!-- hephaestus-provenance:1 <jws> -->` among the trailing markers. Copy comparison ignores it, as it ignores the footer, and a withdrawn copy drops it.
   - The practice page, the conversation, Slack, product-feedback issues and exports follow in later changes. They carry the same string in their own form.
5. **No switch.** No property, setting or role turns the records off.

## Consequences

- Good: a reader's tool checks with the published keys alone that the instance signed a record.
- Bad: the signature alone does not tie a record to the words next to it. A copied record line verifies under someone else's text, and an edited comment keeps a valid record. Only the instance can compare a comment's words with the stored text. The verification change adds that check.
- Good: the system key's rotation and backup procedures cover the signing key with no extra step.
- Bad: anyone who can read a comment can read the model ID. The admin guide says so.
- Bad: a key that leaks lets someone sign text in the instance's name until the key is rotated. Rotation of the provenance key itself is a separate change.

## Confirmation

- `ProvenanceSignerTest`: a record verifies; a changed claim, another key, another issuer, another type and an unknown key do not. Old and new records verify after the system key rotates.
- `ProvenanceKeysIntegrationTest`: two concurrent bootstraps make one key, and a reader who is not signed in gets the public keys only.
- `ScmFeedbackFormatterTest`: comments carry the line, the words cannot start a provenance line, a copy posted before records existed is the same copy, and a withdrawal drops the line.
- `FeedbackPreparationReceiptIntegrationTest`: prepared feedback is signed, and its record marks exactly its words.

## Revisit trigger

- The Code of Practice on marking and labelling publishes an interoperable detection format.
- A self-hosted model with sampling control becomes the common setup.

## Sources

- [EU AI Act, Art. 50](https://artificialintelligenceact.eu/article/50/): the marking duty this record meets.
- [RFC 7515](https://www.rfc-editor.org/rfc/rfc7515) and [RFC 8037](https://www.rfc-editor.org/rfc/rfc8037): JWS and EdDSA in JOSE.
