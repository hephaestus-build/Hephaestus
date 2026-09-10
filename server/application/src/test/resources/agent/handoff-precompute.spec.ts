import assert from "node:assert/strict";
import test from "node:test";
import { fileURLToPath } from "node:url";

import readyAndTraceableHandoff from "../../../main/resources/practices/precompute/ready-and-traceable-handoff.ts";

void test("a testing checklist does not turn a traceable handoff into a test-absence claim", () => {
	const result = readyAndTraceableHandoff(
		fileURLToPath(new URL("./live-practice/", import.meta.url)),
		new Map(),
		{
			pr_number: 1,
			pr_url: "https://example.org/team/project/pull/1",
			repository_full_name: "team/project",
			source_branch: "42-quiz-flow",
			target_branch: "main",
			commit_sha: "abc123",
			body: "Closes #42\n- [x] Tested locally\nOpen the quiz and finish ten questions.",
		},
	);

	assert.equal(result.metrics.traceabilityRefCount, 1);
	assert.equal(result.directions.length, 1);
	assert.match(result.directions[0] ?? "", /#42/);
});
