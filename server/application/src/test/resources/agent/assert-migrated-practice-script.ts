import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

import { z } from "zod";

import {
	event,
	runBundledProgram,
} from "../../../../../../docker/agents/practice-scripts/run-bundled.ts";

const payload = z
	.object({ slug: z.string(), script: z.string(), criteria: z.string() })
	.parse(JSON.parse(await readFile(z.string().parse(process.argv[2]), "utf8")));
const inputs = event("scm.pull_request.merged");
inputs.practice.criteria = payload.criteria;
const item = {
	author: "ada",
	state: "MERGED",
	is_merged: true,
	merged_by: "bea",
	merged_at: "2026-04-13T15:06:54Z",
};
const result = await runBundledProgram(payload.slug, {
	script: payload.script,
	inputs,
	item,
	context: {
		"metadata.json": item,
		"review_threads.json": {
			review_decision: "APPROVED",
			threads: [],
			reviewDecisions: [],
		},
	},
});
if ("error" in result.outcome) {
	throw result.outcome.error;
}
assert.equal(result.reviews.length, 1);
assert.equal(
	result.reviews[0]?.leads?.some((lead) => typeof lead.label === "string"),
	true,
);
assert.ok(!("error" in result.outcome), JSON.stringify(result.outcome));
