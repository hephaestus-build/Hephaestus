import assert from "node:assert/strict";
import test from "node:test";

import triagesTheIssueWithMetadata from "../../../main/resources/practices/precompute/triages-the-issue-with-labels-and-ownership.ts";

void test("a native issue type supplies classification metadata without a label", () => {
	const result = triagesTheIssueWithMetadata("owner/repository", new Map(), {
		issue_type: "Bug",
		labels: [],
		assignees: [],
	});

	assert.equal(result.metrics.hasIssueType, 1);
	assert.equal(result.metrics.labelCount, 0);
	assert.equal(result.directions.length, 1);
	assert.match(result.directions[0] ?? "", /issueType="Bug"/);
});

void test("empty metadata is reported without prescribing triage work", () => {
	const result = triagesTheIssueWithMetadata("owner/repository", new Map(), {});

	assert.equal(result.metrics.hasIssueType, 0);
	assert.deepEqual(result.directions, [
		"Classification metadata: issueType=none, labels=0 [], assignees=0, milestone=none, state=?.",
	]);
});

void test("a stale label remains metadata rather than a judgment about ownership", () => {
	const result = triagesTheIssueWithMetadata("owner/repository", new Map(), {
		labels: ["stale"],
		state: "OPEN",
	});

	assert.deepEqual(result.directions, [
		"Classification metadata: issueType=none, labels=1 [stale], assignees=0, milestone=none, state=OPEN.",
	]);
});
