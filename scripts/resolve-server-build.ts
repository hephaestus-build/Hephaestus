import { appendFile } from "node:fs/promises";

import { requiredEnv } from "./lib/env.ts";
import { asArray, asRecord, asString, parseJson } from "./lib/json.ts";
import { output } from "./lib/process.ts";

const requiredJobs = [
	"App Server: Package",
	"Build / App Server: Generated artifacts",
	"Build / App Server: Database",
	"Build / Webapp: E2E",
	"App Server image / Build linux/amd64 Docker Image",
	"Test / App Server: Unit and architecture",
	"Test / App Server: Integration (application)",
	"Test / App Server: Integration (providers-and-startup)",
	"Security / Dependencies, secrets, and policy",
	"CI Status Gate",
];

function id(value: unknown): number {
	if (typeof value !== "number" || !Number.isSafeInteger(value) || value <= 0) {
		throw new Error("GitHub returned an invalid identifier");
	}
	return value;
}

export async function resolveServerBuild(
	repository: string,
	commit: string,
	defaultBranch: string,
	api: (path: string) => Promise<unknown>,
): Promise<{ runId: number; artifactId: number } | undefined> {
	if (!/^[a-f\d]{40}$/.test(commit)) throw new Error("Expected an exact commit SHA");
	const root = `repos/${repository}/actions`;
	const listing = asRecord(
		await api(
			`${root}/workflows/cicd.yml/runs?event=merge_group&head_sha=${commit}&status=success&per_page=100`,
		),
		"workflow runs",
	);
	for (const value of asArray(listing.workflow_runs, "workflow runs")) {
		const run = asRecord(value, "workflow run");
		if (
			run.event !== "merge_group" ||
			run.status !== "completed" ||
			run.conclusion !== "success" ||
			run.head_sha !== commit ||
			run.path !== ".github/workflows/cicd.yml" ||
			asRecord(run.repository, "repository").full_name !== repository ||
			asRecord(run.head_repository, "head repository").full_name !== repository ||
			!asString(run.head_branch, "head branch").startsWith(`gh-readonly-queue/${defaultBranch}/`)
		)
			continue;
		const runId = id(run.id);
		const repositoryId = id(asRecord(run.repository, "repository").id);
		if (id(asRecord(run.head_repository, "head repository").id) !== repositoryId) continue;
		const jobPages = asArray(
			await api(`${root}/runs/${runId}/jobs?filter=latest&per_page=100`),
			"job pages",
		);
		const jobs = jobPages
			.flatMap((page) => asArray(asRecord(page, "job page").jobs, "jobs"))
			.map((job) => asRecord(job, "job"));
		if (
			!requiredJobs.every((name) =>
				jobs.some((job) => job.name === name && job.conclusion === "success"),
			)
		)
			continue;
		const artifactPages = asArray(
			await api(`${root}/runs/${runId}/artifacts?per_page=100`),
			"artifact pages",
		);
		const artifacts = artifactPages.flatMap((page) =>
			asArray(asRecord(page, "artifact page").artifacts, "artifacts"),
		);
		for (const candidate of artifacts) {
			const artifact = asRecord(candidate, "artifact");
			if (
				artifact.name !== `server-build-${runId}` ||
				artifact.expired !== false ||
				!/^sha256:[a-f\d]{64}$/.test(asString(artifact.digest, "artifact digest"))
			)
				continue;
			const origin = asRecord(artifact.workflow_run, "artifact workflow run");
			if (
				origin.id !== runId ||
				origin.head_sha !== commit ||
				origin.repository_id !== repositoryId ||
				origin.head_repository_id !== repositoryId
			)
				continue;
			return { runId, artifactId: id(artifact.id) };
		}
	}
	return undefined;
}

if (import.meta.main) {
	const result = await resolveServerBuild(
		requiredEnv(process.env, "GITHUB_REPOSITORY"),
		requiredEnv(process.env, "GITHUB_SHA"),
		requiredEnv(process.env, "DEFAULT_BRANCH"),
		async (path) =>
			parseJson(
				await output("gh", [
					"api",
					path,
					...(path.includes("/workflows/") ? [] : ["--paginate", "--slurp"]),
				]),
			),
	);
	if (result) {
		await appendFile(
			requiredEnv(process.env, "GITHUB_OUTPUT"),
			`run-id=${result.runId}\nartifact-id=${result.artifactId}\n`,
		);
		await appendFile(
			requiredEnv(process.env, "GITHUB_STEP_SUMMARY"),
			`Reusing the packaged server validated by [merge-queue CI](${process.env.GITHUB_SERVER_URL}/${process.env.GITHUB_REPOSITORY}/actions/runs/${result.runId}) for this exact commit. All current-run validation and image publication remain enabled.\n`,
		);
	}
}
