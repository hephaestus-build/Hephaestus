# Task-envelope fixture

`practice-review.json` is shared by the Java writer and TypeScript runner tests. The Java test
compares parsed JSON, not formatting. Schema evolution follows the
[workspace ABI](https://docs.hephaestus.build/contributor/agent/agent-workspace-abi#versioning-policy).

To regenerate an intentionally changed envelope, run from the repository root:

```sh
./server/gradlew -p server :application:test --tests TaskEnvelopeFixtureTest \
  -PtestJvmArgs=-Dhephaestus.snapshot.regenerate=true
```

Regeneration overwrites the fixture instead of asserting. Review the diff, then run the test
without the regeneration flag and run `vp run test:agents` to check the consumer.
