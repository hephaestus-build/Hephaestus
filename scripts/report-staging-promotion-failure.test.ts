import assert from "node:assert/strict";
import { test } from "node:test";

import { MARKER, planFailureReport, TITLE } from "./report-staging-promotion-failure.ts";

const commit = "c".repeat(40);
const runUrl = "https://github.com/owner/repo/actions/runs/1/attempts/1";

await test("the first failure opens one tracking issue that names the commit and the run", () => {
	const report = planFailureReport([{ number: 7, body: "an unrelated ci issue" }], commit, runUrl);
	assert.ok(report.kind === "create");
	assert.equal(report.title, TITLE);
	assert.ok(report.body.startsWith(MARKER));
	assert.ok(report.body.includes(commit) && report.body.includes(runUrl));
});

await test("a later failure comments on the open tracking issue instead of opening another", () => {
	assert.deepEqual(
		planFailureReport([{ number: 9, body: `${MARKER}\nearlier failure` }], commit, runUrl),
		{
			kind: "comment",
			issue: 9,
			body: `The automatic promotion of \`${commit}\` failed: ${runUrl}`,
		},
	);
});
