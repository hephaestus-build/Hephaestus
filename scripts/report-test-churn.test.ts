import assert from "node:assert/strict";
import { test } from "node:test";

import { parseLog, rank, suiteOf } from "./report-test-churn.ts";

void test("a suite that refactors modified ranks above one that more commits changed", () => {
	const ranked = rank(
		parseLog(
			[
				"\u0001refactor(server): split the handler",
				"M\tserver/src/main/java/Handler.java",
				"M\tserver/src/test/java/HandlerTest.java",
				"\u0001feat(server): add a route",
				"M\tserver/src/test/java/RouteTest.java",
				"\u0001fix(server): reject an empty route",
				"M\tserver/src/test/java/RouteTest.java",
				"\u0001feat(server): add a second route",
				"M\tserver/src/test/java/RouteTest.java",
			].join("\n"),
		),
	);
	assert.deepEqual(ranked, [
		{ suite: "server/src/test/java/HandlerTest.java", commits: 1, behaviorPreserving: 1 },
		{ suite: "server/src/test/java/RouteTest.java", commits: 3, behaviorPreserving: 0 },
	]);
});

void test("among suites that refactors never modified, more commits rank first", () => {
	const ranked = rank(
		parseLog(
			[
				"\u0001feat(webapp): add the list",
				"M\twebapp/src/List.test.tsx",
				"M\twebapp/src/Card.test.tsx",
				"\u0001fix(webapp): sort the list",
				"M\twebapp/src/List.test.tsx",
			].join("\n"),
		),
	);
	assert.deepEqual(
		ranked.map((churn) => churn.suite),
		["webapp/src/List.test.tsx", "webapp/src/Card.test.tsx"],
	);
});

void test("a test that a refactor only adds is not a behavior-preserving modification", () => {
	const ranked = rank(
		parseLog("\u0001refactor(webapp): extract the list\nA\twebapp/src/List.test.tsx\n"),
	);
	assert.deepEqual(ranked, [
		{ suite: "webapp/src/List.test.tsx", commits: 1, behaviorPreserving: 0 },
	]);
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
