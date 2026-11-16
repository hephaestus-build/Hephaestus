import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import { parse } from "yaml";

import { asArray, asRecord } from "./lib/json.ts";

void test("a secret reaches a step only through its environment", async () => {
	const workflow = asRecord(
		parse(await readFile(".github/workflows/feedback-eval-panel.yml", "utf8")),
		"panel workflow",
	);
	const references: string[] = [];
	for (const [name, job] of Object.entries(asRecord(workflow.jobs, "jobs"))) {
		const record = asRecord(job, name);
		assert.doesNotMatch(JSON.stringify(record.if ?? ""), /secrets\./u, `${name} if`);
		for (const step of asArray(record.steps ?? [], `${name} steps`)) {
			const { env, ...rest } = asRecord(step, `${name} step`);
			// `run`, `with` and `if` hold no secret: a value there can reach a command line or a log.
			assert.doesNotMatch(JSON.stringify(rest), /secrets\./u, JSON.stringify(rest).slice(0, 80));
			for (const value of Object.values(asRecord(env ?? {}, `${name} env`))) {
				if (String(value).includes("secrets.")) {
					references.push(String(value));
				}
			}
		}
	}
	assert.ok(references.length > 0);
	for (const reference of references) {
		assert.match(reference, /^\$\{\{ secrets\.CI_LLM_\w+(?: != '')? \}\}$/u, reference);
	}
});
