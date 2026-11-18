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

void test("a configured endpoint is probed before any job composes, in the panel and in the decision", async () => {
	for (const file of [
		".github/workflows/feedback-eval-panel.yml",
		".github/workflows/feedback-eval-decision.yml",
	]) {
		const jobs = asRecord(asRecord(parse(await readFile(file, "utf8")), file).jobs, `${file} jobs`);
		const provider = asRecord(jobs.provider, `${file} provider`);
		const probe = asArray(provider.steps, `${file} provider steps`)
			.map((step) => asRecord(step, `${file} step`))
			.find((step) => step.id === "probe");
		assert.ok(probe !== undefined, `${file} has no probe step`);
		assert.match(String(probe.run), /^node scripts\/feedback-eval\/endpoint-probe\.ts /u);
		assert.match(
			String(asRecord(provider.outputs, `${file} outputs`).reachable),
			/steps\.probe\.outputs\.reachable/u,
		);
		// Every job that calls the endpoint needs the provider job, so a failed probe skips it.
		for (const [name, job] of Object.entries(jobs)) {
			const record = asRecord(job, name);
			if (asRecord(record.concurrency ?? {}, `${name} concurrency`).group === "ci-model-endpoint") {
				assert.ok(asArray(record.needs, `${name} needs`).includes("provider"), `${file} ${name}`);
			}
		}
	}
});
