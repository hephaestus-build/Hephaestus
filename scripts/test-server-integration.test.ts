import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import { integrationRuns, keepShardReports, selectsTests } from "./test-server-integration.ts";

void test("a local run takes the review shard and each numbered shard, one after another", () => {
	assert.deepEqual(integrationRuns(undefined, [], 3), ["review", "1", "2", "3"]);
	assert.deepEqual(integrationRuns("", ["--profile", "-PprofileTests=true"], 2), [
		"review",
		"1",
		"2",
	]);
});

void test("a CI job's shard and a run that selects its tests are one Gradle run", () => {
	assert.deepEqual(integrationRuns("4", [], 8), [undefined]);
	assert.deepEqual(integrationRuns(undefined, ["--tests", "*FooIntegrationTest"], 8), [undefined]);
});

void test("every spelling of a test selection selects the tests", () => {
	for (const selection of [
		["--tests", "*Foo"],
		["--tests=*Foo"],
		["-PcriteriaCases=dependency-label"],
		["-P", "criteriaCases=dependency-label"],
		["--project-prop", "criteriaCases=dependency-label"],
		["--project-prop=criteriaCases=dependency-label"],
	]) {
		assert.equal(selectsTests(["--continue", ...selection]), true, selection.join(" "));
	}
	for (const other of [["--profile"], ["-PprofileTests=true"], ["-P", "testJvmArgs=-Xlog:gc"]]) {
		assert.equal(selectsTests(other), false, other.join(" "));
	}
});

void test("a shard's reports move aside, so the next shard cannot replace them", () => {
	const build = mkdtempSync(path.join(tmpdir(), "integration-reports-"));
	const results = path.join(build, "test-results/integrationTest");
	mkdirSync(results, { recursive: true });
	writeFileSync(path.join(results, "TEST-Failed.xml"), "first run");
	const stale = path.join(build, "test-results/integrationTest-2");
	mkdirSync(stale, { recursive: true });
	writeFileSync(path.join(stale, "TEST-Stale.xml"), "earlier invocation");

	assert.deepEqual(keepShardReports(build, "2"), [stale]);
	assert.equal(readFileSync(path.join(stale, "TEST-Failed.xml"), "utf8"), "first run");
	assert.throws(() => readFileSync(path.join(stale, "TEST-Stale.xml")));
	assert.deepEqual(keepShardReports(build, "3"), []);
});
