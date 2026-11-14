import assert from "node:assert/strict";
import { test } from "node:test";

import { parseLog, rank, suiteOf } from "./report-test-churn.ts";

void test("a suite that a refactor modified ranks above one that only features changed", () => {
	const ranked = rank(
		parseLog(
			[
				"\u0001refactor(server): split the handler",
				"M\tserver/src/main/java/Handler.java",
				"M\tserver/src/test/java/HandlerTest.java",
				"\u0001feat(server): add a route",
				"M\tserver/src/test/java/RouteTest.java",
				"M\tserver/src/test/java/HandlerTest.java",
				"\u0001perf(ci): shorten the queue tier",
				"M\tscripts/ci-contract.test.ts",
			].join("\n"),
		),
	);
	assert.deepEqual(ranked, [
		{ suite: "server/src/test/java/HandlerTest.java", commits: 2, behaviorPreserving: 1 },
		{ suite: "scripts/ci-contract.test.ts", commits: 1, behaviorPreserving: 1 },
	]);
});

void test("a test that a refactor only adds is not churn", () => {
	const ranked = rank(
		parseLog("\u0001refactor(webapp): extract the list\nA\twebapp/src/List.test.tsx\n"),
	);
	assert.deepEqual(ranked, []);
});

void test("golden files count once per commit, as their resource directory", () => {
	assert.equal(
		suiteOf(
			"server/application/src/test/resources/agent/golden-prompts/composition-turns/issue/brief.md",
		),
		"server/application/src/test/resources/agent/golden-prompts",
	);
	const ranked = rank(
		parseLog(
			[
				"\u0001refactor(server): move the composer",
				"M\tserver/application/src/test/resources/agent/golden-prompts/tools.json",
				"M\tserver/application/src/test/resources/agent/golden-prompts/composition-contracts.md",
			].join("\n"),
		),
	);
	assert.deepEqual(ranked, [
		{
			suite: "server/application/src/test/resources/agent/golden-prompts",
			commits: 1,
			behaviorPreserving: 1,
		},
	]);
});

void test("production files are not suites", () => {
	assert.equal(suiteOf("server/application/src/main/java/Handler.java"), undefined);
	assert.equal(suiteOf("webapp/src/components/List.tsx"), undefined);
});
