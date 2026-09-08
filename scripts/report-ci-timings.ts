import { asArray, asRecord, asString, readJsonFile } from "./lib/json.ts";

function timestamp(value: unknown, label: string): number {
	const parsed = Date.parse(asString(value, label));
	if (!Number.isFinite(parsed)) throw new Error(`Invalid timestamp: ${label}`);
	return parsed;
}

export function summarizeCiTimings(runValue: unknown, jobsValue: unknown) {
	const run = asRecord(runValue, "run");
	if (run.status !== "completed") throw new Error("Run is not completed");
	for (const key of ["id", "run_attempt"]) {
		if (!Number.isSafeInteger(run[key]) || typeof run[key] !== "number" || run[key] < 1)
			throw new Error(`Invalid run ${key}`);
	}
	const response = asRecord(jobsValue, "jobs response");
	const jobs = asArray(response.jobs, "jobs").map((value) => asRecord(value, "job"));
	if (jobs.length !== response.total_count)
		throw new Error("Incomplete jobs response; fetch every page");
	for (const job of jobs) {
		if (job.run_id !== run.id || job.run_attempt !== run.run_attempt)
			throw new Error("Jobs must belong to this run and attempt");
	}
	const executed = jobs.filter((job) => job.conclusion !== "skipped");
	if (executed.length === 0) throw new Error("No executed jobs");
	const gate = jobs.find((job) => job.name === "CI Status Gate");
	if (!gate || gate.status !== "completed" || gate.conclusion === "skipped")
		throw new Error("Completed CI Status Gate is missing");
	const created = timestamp(run.created_at, "run.created_at");
	const gateEnd = timestamp(gate.completed_at, "gate.completed_at");
	const timings = executed.map((job) => {
		const queued = timestamp(job.created_at, "job.created_at");
		const started = timestamp(job.started_at, "job.started_at");
		const completed = timestamp(job.completed_at, "job.completed_at");
		if (completed < started || started < queued || queued < created)
			throw new Error("Job timestamps are out of order");
		return {
			name: asString(job.name, "job.name"),
			url: asString(job.html_url, "job.html_url"),
			creationToStartSeconds: (started - queued) / 1000,
			executionSeconds: (completed - started) / 1000,
			started,
			queued,
		};
	});
	return {
		url: asString(run.html_url, "run.html_url"),
		event: asString(run.event, "run.event"),
		attempt: run.run_attempt,
		conclusion: asString(gate.conclusion, "gate.conclusion"),
		// Retries retain created_at: never mix them into first-attempt latency percentiles.
		verdictSeconds: (gateEnd - created) / 1000,
		beforeJobsCreatedSeconds: (Math.min(...timings.map((job) => job.queued)) - created) / 1000,
		firstRunnerSeconds: (Math.min(...timings.map((job) => job.started)) - created) / 1000,
		// Creation-to-start can include dependency waiting; it is not a pure runner-queue metric.
		// Neither sum is an additive portion of verdict time: jobs overlap.
		jobStartDelaySeconds: timings.reduce((sum, job) => sum + job.creationToStartSeconds, 0),
		runnerSeconds: timings.reduce((sum, job) => sum + job.executionSeconds, 0),
		jobs: timings
			.toSorted((a, b) => b.executionSeconds - a.executionSeconds)
			.map(({ started: _started, queued: _queued, ...job }) => job),
	};
}

if (import.meta.main) {
	const [runFile, jobsFile] = process.argv.slice(2);
	if (!runFile || !jobsFile) throw new Error("Usage: report:ci-timings <run.json> <jobs.json>");
	const [run, jobs] = await Promise.all([readJsonFile(runFile), readJsonFile(jobsFile)]);
	process.stdout.write(`${JSON.stringify(summarizeCiTimings(run, jobs), null, 2)}\n`);
}
