# Regenerating task-fixtures/v1/

These fixtures are byte-equal expectations for `TaskEnvelopeWriter` output. They are
deterministic — `ObjectMapper` is configured with `ORDER_MAP_ENTRIES_BY_KEYS=true` and
pretty-printer with the default 2-space indent.

## When to regenerate

- Bump `TaskEnvelope.SCHEMA_VERSION` and add a required field to an existing `Task` permit:
  regenerate so the fixture captures the new shape.
- Add a new `Task` permit (e.g., `MentorChatTask`): add a new fixture file alongside
  `practice-review.json`, do **not** modify the existing one.
- Change the pretty-printer settings or `ObjectMapper` configuration in `TaskEnvelopeWriter`:
  regenerate (a deliberate signal that the byte format changed).

## How to regenerate

Run the failing fixture test with the `-Dhephaestus.snapshot.regenerate=true` system property:

```
cd server
./gradlew :application:test --tests TaskEnvelopeFixtureTest \
  -PtestJvmArgs=-Dhephaestus.snapshot.regenerate=true
```

That mode overwrites the fixture on disk instead of asserting. Diff the result, commit if intended.

## Comparison policy

Comparison is **byte-identical** against the committed file. Because the writer pins JSON
key order and the test pins all inputs (jobId, workspaceId, prompt content), this gate
is stable across machines, OS, and JVM versions. If the test ever flakes, the cause is
the writer drifting — not env noise.
