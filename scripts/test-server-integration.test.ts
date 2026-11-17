import assert from "node:assert/strict";
import test from "node:test";

import { integrationRuns } from "./test-server-integration.ts";

void test("a local run takes the review shard and each numbered shard, one after another", () => {
	assert.deepEqual(integrationRuns(undefined, [], 3), ["review", "1", "2", "3"]);
	assert.deepEqual(integrationRuns("", ["--profile"], 2), ["review", "1", "2"]);
});

void test("a CI job's shard and a run with --tests are one Gradle run", () => {
	assert.deepEqual(integrationRuns("4", [], 8), [undefined]);
	assert.deepEqual(integrationRuns(undefined, ["--tests", "*FooIntegrationTest"], 8), [undefined]);
});
