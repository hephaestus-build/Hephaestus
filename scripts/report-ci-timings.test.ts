import assert from "node:assert/strict";
import { test } from "node:test";

import { summarizeCiTimings } from "./report-ci-timings.ts";

const at = (seconds: number) => new Date(seconds * 1000).toISOString();
const run = {
	id: 1,
	run_attempt: 1,
	status: "completed",
	created_at: at(0),
	updated_at: at(999),
	html_url: "https://github.com/example/project/actions/runs/1",
	event: "pull_request",
};
const job = (name: string, created: number, start: number, end: number) => ({
	name,
	run_id: 1,
	run_attempt: 1,
	status: "completed",
	conclusion: "success",
	created_at: at(created),
	started_at: at(start),
	completed_at: at(end),
	html_url: `${run.html_url}/job/1`,
});
const jobs = {
	total_count: 3,
	jobs: [job("Build", 10, 30, 90), job("Tests", 10, 20, 100), job("CI Status Gate", 100, 110, 115)],
};

void test("separates admission, job queue and execution without treating parallel work as wall time", () => {
	const result = summarizeCiTimings(run, jobs);
	assert.equal(result.verdictSeconds, 115); // updated_at is not the verdict.
	assert.equal(result.beforeJobsCreatedSeconds, 10);
	assert.equal(result.firstRunnerSeconds, 20);
	assert.equal(result.jobStartDelaySeconds, 40);
	assert.equal(result.runnerSeconds, 145);
	assert.equal(result.jobs[0]?.name, "Tests");
});

void test("excludes skipped placeholders, including GitHub's reversed placeholder timestamps", () => {
	const skipped = { ...job("Skipped", 0, 1, 0), conclusion: "skipped" };
	assert.deepEqual(
		summarizeCiTimings(run, { total_count: 4, jobs: [...jobs.jobs, skipped] }),
		summarizeCiTimings(run, jobs),
	);
});

void test("reports failures and retries explicitly rather than hiding them in successful first attempts", () => {
	const failed = jobs.jobs.map((entry) => ({ ...entry, conclusion: "failure", run_attempt: 2 }));
	const result = summarizeCiTimings({ ...run, run_attempt: 2 }, { total_count: 3, jobs: failed });
	assert.equal(result.attempt, 2);
	assert.equal(result.conclusion, "failure");
});

void test("rejects incomplete, mixed-attempt and malformed API responses", () => {
	assert.throws(() => summarizeCiTimings({ ...run, status: "in_progress" }, jobs), /not completed/);
	assert.throws(() => summarizeCiTimings(run, { ...jobs, total_count: 4 }), /Incomplete/);
	assert.throws(() => summarizeCiTimings({ ...run, run_attempt: 2 }, jobs), /this run and attempt/);
	assert.throws(() => summarizeCiTimings({ ...run, created_at: "bad" }, jobs), /Invalid timestamp/);
	assert.throws(() => summarizeCiTimings(run, { total_count: 0, jobs: [] }), /No executed/);
	assert.throws(
		() =>
			summarizeCiTimings(run, {
				...jobs,
				jobs: jobs.jobs.map((entry) =>
					entry.name === "CI Status Gate" ? { ...entry, conclusion: "skipped" } : entry,
				),
			}),
		/Gate is missing/,
	);
	assert.throws(() => summarizeCiTimings({ ...run, created_at: at(15) }, jobs), /out of order/);
	assert.throws(
		() => summarizeCiTimings(run, { total_count: 1, jobs: [jobs.jobs[0]] }),
		/Gate is missing/,
	);
	assert.throws(
		() =>
			summarizeCiTimings(run, { ...jobs, jobs: [job("Build", 30, 20, 40), ...jobs.jobs.slice(1)] }),
		/out of order/,
	);
});
