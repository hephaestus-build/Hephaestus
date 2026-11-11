// The release preflight's check that the newest nightly Kubernetes run on main passed. A merge
// group installs and upgrades the combined tree but runs none of the latest leg's drills, so a
// break that only two merged changes cause together shows first in the nightly run.
import { appendFile } from "node:fs/promises";

import { requiredEnv } from "./lib/env.ts";
import { asArray, asRecord, asString } from "./lib/json.ts";
import { ghApi } from "./resolve-server-build.ts";

type Api = (path: string) => Promise<unknown>;

/** A nightly run older than this is no evidence for a release. */
export const maxAgeHours = 48;

/** The nightly jobs that qualify the cluster; the sandbox footprint benchmark is not one.
 * Keep Chart for runs still inside the admission window from before lint joined its consumer. */
function isClusterJob(name: string): boolean {
	return (
		name === "Newest main build" ||
		name === "Kubernetes / Chart" ||
		/^Kubernetes(?: \(.*\))? \/ (?:k3s |kind )/u.test(name)
	);
}

/**
 * Why the newest completed nightly Kubernetes run on main does not qualify a release, or
 * `undefined` when it does. A cancelled run says nothing, so the newest run that ran is judged.
 */
export async function nightlyRefusal(
	repository: string,
	api: Api,
	now: Date,
): Promise<{ refusal: string | undefined; runUrl: string | undefined }> {
	const root = `repos/${repository}/actions`;
	const listing = asRecord(
		await api(
			`${root}/workflows/kubernetes-nightly.yml/runs?branch=main&status=completed&per_page=20`,
		),
		"workflow runs",
	);
	const run = asArray(listing.workflow_runs, "workflow runs")
		.map((value) => asRecord(value, "workflow run"))
		.find(
			(candidate) => candidate.conclusion !== "cancelled" && candidate.conclusion !== "skipped",
		);
	if (run === undefined) {
		return { refusal: "No nightly Kubernetes run on main has completed.", runUrl: undefined };
	}
	const runUrl = asString(run.html_url, "run URL");
	const ageHours = (now.getTime() - Date.parse(asString(run.created_at, "run start"))) / 3_600_000;
	if (!(ageHours <= maxAgeHours)) {
		return {
			refusal: `The newest nightly Kubernetes run on main started more than ${maxAgeHours} hours ago.`,
			runUrl,
		};
	}
	const jobs = asArray(
		await api(`${root}/runs/${String(run.id)}/jobs?filter=latest&per_page=100`),
		"job pages",
	)
		.flatMap((page) => asArray(asRecord(page, "job page").jobs, "jobs"))
		.map((value) => asRecord(value, "job"))
		.filter((job) => isClusterJob(asString(job.name, "job name")));
	const failed = jobs
		.filter((job) => job.conclusion !== "success" && job.conclusion !== "skipped")
		.map((job) => asString(job.name, "job name"));
	if (failed.length > 0) {
		return {
			refusal: `The newest nightly Kubernetes run on main failed: ${failed.join(", ")}.`,
			runUrl,
		};
	}
	if (
		!jobs.some(
			(job) =>
				/^Kubernetes(?: \(.*\))? \/ (?:k3s |kind )/u.test(asString(job.name, "job name")) &&
				job.conclusion === "success",
		)
	) {
		return {
			refusal: "The newest nightly Kubernetes run on main qualified no cluster leg.",
			runUrl,
		};
	}
	return { refusal: undefined, runUrl };
}

if (import.meta.main) {
	const { refusal, runUrl } = await nightlyRefusal(
		requiredEnv(process.env, "GITHUB_REPOSITORY"),
		ghApi,
		new Date(),
	);
	const summary = refusal ?? "The newest nightly Kubernetes run on main passed.";
	await appendFile(
		requiredEnv(process.env, "GITHUB_STEP_SUMMARY"),
		`${summary}${runUrl === undefined ? "" : ` [Run](${runUrl})`}\n`,
	);
	if (refusal !== undefined) {
		console.error(`::error::${refusal} Fix main, then run the nightly workflow again.`);
		process.exitCode = 1;
	}
}
