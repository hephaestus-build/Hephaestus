import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { runInNewContext } from "node:vm";

import { isMap, isSeq, parseDocument } from "yaml";

const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
const diagnostics = parseDocument(await readFile(".github/workflows/ci-diagnostics.yml", "utf8"));
const steps = diagnostics.getIn(["jobs", "report", "steps"]);
assert.ok(isSeq(steps));
const reportStep = steps.items[0];
assert.ok(isMap(reportStep));
const script = reportStep.getIn(["with", "script"]);
assert.equal(typeof script, "string");

function jsonOf(value: unknown): unknown {
	assert.ok(isMap(value) || isSeq(value));
	return value.toJSON();
}

const run = {
	id: 42,
	run_attempt: 2,
	name: "CI/CD",
	status: "completed",
	html_url: "https://github.com/owner/repo/actions/runs/42/attempts/2",
	created_at: "2026-10-13T18:00:00Z",
	run_started_at: "2026-10-13T20:00:00Z",
};
const job = {
	run_id: run.id,
	status: "completed",
	name: "CI Status Gate",
	conclusion: "success",
	created_at: "2026-10-13T20:00:00Z",
	started_at: "2026-10-13T20:01:00Z",
	completed_at: "2026-10-13T20:02:00Z",
	steps: [
		{
			name: "Evaluate CI results",
			conclusion: "success",
			started_at: "2026-10-13T20:01:00Z",
			completed_at: "2026-10-13T20:01:01Z",
		},
	],
};

async function report(
	options: {
		run?: unknown;
		jobs?: unknown[];
		upstream?: unknown;
		failRead?: boolean;
		failWrite?: boolean;
	} = {},
) {
	const rendered: unknown[] = [];
	const output = {
		addHeading: (text: string) => {
			rendered.push(text);
			return output;
		},
		addRaw: (text: string) => {
			rendered.push(text);
			return output;
		},
		addTable: (rows: unknown) => {
			rendered.push(rows);
			return output;
		},
		addLink: (label: string, url: string) => {
			rendered.push([label, url]);
			return output;
		},
		write: async () => {
			if (options.failWrite === true) {
				throw new Error("Summary unavailable");
			}
			rendered.push("written");
		},
	};
	const identity = { owner: "owner", repo: "repo", run_id: 42, attempt_number: 2 };
	const listJobs = Symbol("listJobsForWorkflowRunAttempt");
	const completed: unknown = runInNewContext(`(async () => { ${String(script)} })()`, {
		core: { summary: output },
		context: {
			runId: 999,
			payload: { workflow_run: options.upstream ?? run },
			repo: { owner: "owner", repo: "repo" },
		},
		github: {
			rest: {
				actions: {
					getWorkflowRunAttempt: async (input: unknown) => {
						assert.equal(JSON.stringify(input), JSON.stringify(identity));
						if (options.failRead === true) {
							throw new Error("HTTP 502");
						}
						return { data: options.run ?? run };
					},
					listJobsForWorkflowRunAttempt: listJobs,
				},
			},
			paginate: async (endpoint: unknown, input: unknown) => {
				assert.equal(endpoint, listJobs);
				assert.equal(JSON.stringify(input), JSON.stringify({ ...identity, per_page: 100 }));
				return options.jobs ?? [job];
			},
		},
	});
	await completed;
	return JSON.stringify(rendered);
}

void test("diagnostics cannot change or delay the required workflow verdict", () => {
	const gate = workflow.getIn(["jobs", "all-ci-passed"]);
	assert.ok(isMap(gate));
	assert.equal(gate.get("continue-on-error"), undefined);
	assert.deepEqual(jsonOf(gate.get("permissions")), { actions: "read", statuses: "write" });
	const gateSteps = gate.get("steps");
	assert.ok(isSeq(gateSteps));
	assert.deepEqual(
		gateSteps.items.map((item) => {
			assert.ok(isMap(item));
			assert.doesNotMatch(String(item.get("uses")), /checkout|timeline|setup|^\.\//u);
			return item.get("name");
		}),
		["Evaluate CI results", "Create commit status"],
	);
	assert.deepEqual(jsonOf(diagnostics.getIn(["on", "workflow_run", "workflows"])), ["CI/CD"]);
	assert.deepEqual(jsonOf(diagnostics.getIn(["on", "workflow_run", "types"])), ["completed"]);
	assert.deepEqual(jsonOf(diagnostics.get("permissions")), {});
	assert.deepEqual(jsonOf(diagnostics.getIn(["jobs", "report", "permissions"])), {
		actions: "read",
	});
	assert.equal(steps.items.length, 1);
	assert.match(String(reportStep.get("uses")), /^actions\/github-script@[a-f0-9]{40}$/u);
	assert.doesNotMatch(
		String(script),
		/createCommitStatus|createCheckRun|secrets\.|exec\(|require\(|downloadArtifact/u,
	);
	assert.doesNotMatch(String(script), /\$\{\{/u);
});

void test("release consumers remain bound to required CI, not diagnostics", async () => {
	for (const file of ["release.yml", "version-pr.yml", "continuous-staging.yml"]) {
		const consumer = parseDocument(await readFile(`.github/workflows/${file}`, "utf8"));
		assert.deepEqual(jsonOf(consumer.getIn(["on", "workflow_run", "workflows"])), ["CI/CD"]);
	}
});

void test("the report selects the upstream attempt with jobs that omit an attempt field", async () => {
	const text = await report();
	assert.ok(text.includes("Upstream run 42, attempt 2"));
	assert.ok(text.includes("Attempt-to-gate time"));
	assert.ok(text.includes('["Attempt-to-gate time","2m 0s"]'));
	assert.ok(!text.includes("122m 0s"));
	assert.ok(text.includes("Evaluate CI results"));
	assert.ok(text.includes("does not publish or change"));
	for (const invalid of [
		{ ...run, id: 999 },
		{ ...run, run_attempt: 3 },
		{ ...run, status: "in_progress" },
		{ ...run, name: "Other" },
	]) {
		await assert.rejects(report({ run: invalid }));
	}
	for (const invalid of [{ id: 42 }, { id: 0, run_attempt: 2 }, { id: 42, run_attempt: 0 }]) {
		await assert.rejects(report({ upstream: invalid }));
	}
	for (const invalid of [
		{ ...job, run_id: 999 },
		{ ...job, status: "in_progress" },
		{ ...job, name: "Other" },
	]) {
		await assert.rejects(report({ jobs: [invalid] }));
	}
	await assert.rejects(report({ jobs: [] }));
});

void test("reporting outages remain diagnostics failures with no status-writing API", async () => {
	await assert.rejects(report({ failRead: true }), /HTTP 502/u);
	await assert.rejects(report({ failWrite: true }), /Summary unavailable/u);
	// The test sandbox has no repos/statuses API: even a successful report cannot publish a verdict.
	const text = await report();
	assert.ok(text.includes("written"));
});

void test("failed and cancelled gates remain distinct from success and unavailable timing", async () => {
	for (const conclusion of ["failure", "cancelled", "timed_out"]) {
		const text = await report({ jobs: [{ ...job, conclusion }] });
		assert.ok(text.includes(conclusion));
	}
	const text = await report({ jobs: [{ ...job, started_at: null, completed_at: null }] });
	assert.ok(text.includes("Unavailable"));
	assert.ok(!text.includes("0m 0s"));
	const missingAttemptStart = await report({ run: { ...run, run_started_at: null } });
	assert.ok(missingAttemptStart.includes('["Attempt-to-gate time","Unavailable"]'));
});

void test("job and step names are escaped and skips are not assigned invented timing", async () => {
	const text = await report({
		jobs: [
			job,
			{
				...job,
				name: "<other>",
				conclusion: "skipped",
				started_at: null,
				completed_at: null,
				steps: [{ ...job.steps[0], name: "<step>" }],
			},
		],
	});
	assert.ok(text.includes("&lt;other&gt;"));
	assert.ok(text.includes("&lt;step&gt;"));
	assert.ok(text.includes("skipped"));
	assert.ok(text.includes("Unavailable"));
});
