import { appendFile } from "node:fs/promises";

import { requiredEnv } from "./lib/env.ts";
import { asArray, asRecord, asString, parseJson } from "./lib/json.ts";
import { output } from "./lib/process.ts";

const requiredJobs = [
	"App Server: Package",
	"Build / App Server: Generated artifacts",
	"Build / App Server: Database",
	"Build / App Server: PostgreSQL drills",
	"Build / Webapp: E2E",
	"App Server image / Build linux/amd64 Docker Image",
	"App Server image / Build linux/arm64 Docker Image",
	"Test / App Server: Unit and architecture",
	"Test / App Server: Integration (application)",
	"Test / App Server: Integration (providers)",
	"Test / App Server: Integration (runtime-and-startup)",
	"Test / App Server: Integration (practices-and-agent)",
	"Security / Dependencies, secrets, and policy",
	"CI Status Gate",
];

function id(value: unknown): number {
	if (typeof value !== "number" || !Number.isSafeInteger(value) || value <= 0) {
		throw new Error("GitHub returned an invalid identifier");
	}
	return value;
}

/** Whether a listed run is a successful merge-queue CI/CD run of `commit` in `repository`. */
function isQueueRunOf(
	run: Record<string, unknown>,
	repository: string,
	commit: string,
	defaultBranch: string,
): boolean {
	return (
		run.event === "merge_group" &&
		run.status === "completed" &&
		run.conclusion === "success" &&
		run.head_sha === commit &&
		run.path === ".github/workflows/cicd.yml" &&
		asRecord(run.repository, "repository").full_name === repository &&
		asRecord(run.head_repository, "head repository").full_name === repository &&
		asString(run.head_branch, "head branch").startsWith(`gh-readonly-queue/${defaultBranch}/`)
	);
}

/** The server build artifact of `runId`, bound to the run and commit it must have come from. */
function serverBuildArtifact(
	artifacts: readonly unknown[],
	runId: number,
	commit: string,
	repositoryId: number,
): number | undefined {
	for (const candidate of artifacts) {
		const artifact = asRecord(candidate, "artifact");
		if (
			artifact.name !== `server-build-${runId}` ||
			artifact.expired !== false ||
			!/^sha256:[a-f\d]{64}$/u.test(asString(artifact.digest, "artifact digest"))
		) {
			continue;
		}
		const origin = asRecord(artifact.workflow_run, "artifact workflow run");
		if (
			origin.id !== runId ||
			origin.head_sha !== commit ||
			origin.repository_id !== repositoryId ||
			origin.head_repository_id !== repositoryId
		) {
			continue;
		}
		return id(artifact.id);
	}
	return undefined;
}

type Api = (path: string) => Promise<unknown>;

/**
 * The job that publishes each image's index in a CI/CD run, by the image key that the image builds
 * of a default-branch push read from `reuse_images`.
 */
export const imageManifests = {
	"application-server": "App Server image / Create manifest",
	webapp: "Docker / Webapp / Create manifest",
	"agent-pi": "Docker / Agent: Pi / Create manifest",
	postgres: "Docker / Postgres (pg_partman) / Create manifest",
} as const;

/**
 * The successful merge-queue CI/CD runs of exactly `commit` in which every job in `jobNames`
 * succeeded. The queue squashes, so a merge group's commit is the one its push to the default
 * branch carries.
 */
async function* queueRuns(
	repository: string,
	commit: string,
	defaultBranch: string,
	api: Api,
	jobNames: readonly string[],
): AsyncGenerator<{ runId: number; repositoryId: number; jobs: Record<string, unknown>[] }> {
	if (!/^[a-f\d]{40}$/u.test(commit)) {
		throw new Error("Expected an exact commit SHA");
	}
	const root = `repos/${repository}/actions`;
	const listing = asRecord(
		await api(
			`${root}/workflows/cicd.yml/runs?event=merge_group&head_sha=${commit}&status=success&per_page=100`,
		),
		"workflow runs",
	);
	for (const value of asArray(listing.workflow_runs, "workflow runs")) {
		const run = asRecord(value, "workflow run");
		if (!isQueueRunOf(run, repository, commit, defaultBranch)) {
			continue;
		}
		const runId = id(run.id);
		const repositoryId = id(asRecord(run.repository, "repository").id);
		if (id(asRecord(run.head_repository, "head repository").id) !== repositoryId) {
			continue;
		}
		const jobPages = asArray(
			await api(`${root}/runs/${runId}/jobs?filter=latest&per_page=100`),
			"job pages",
		);
		const jobs = jobPages
			.flatMap((page) => asArray(asRecord(page, "job page").jobs, "jobs"))
			.map((job) => asRecord(job, "job"));
		if (
			!jobNames.every((name) =>
				jobs.some((job) => job.name === name && job.conclusion === "success"),
			)
		) {
			continue;
		}
		yield { runId, repositoryId, jobs };
	}
}

/**
 * The merge-queue run whose images and verdict a push of the same commit may reuse, and the images
 * it published. A merge group does not build an image that its diff left alone, so only these can
 * pass the push's same-commit provenance check.
 */
export async function resolveQueueRun(
	repository: string,
	commit: string,
	defaultBranch: string,
	api: Api,
): Promise<{ runId: number; images: string[] } | undefined> {
	const first = await queueRuns(repository, commit, defaultBranch, api, ["CI Status Gate"]).next();
	if (first.done === true) {
		return undefined;
	}
	const { runId, jobs } = first.value;
	const images = Object.entries(imageManifests)
		.filter(([, name]) => jobs.some((job) => job.name === name && job.conclusion === "success"))
		.map(([image]) => image);
	return { runId, images };
}

export async function resolveServerBuild(
	repository: string,
	commit: string,
	defaultBranch: string,
	api: Api,
): Promise<{ runId: number; artifactId: number } | undefined> {
	const root = `repos/${repository}/actions`;
	for await (const { runId, repositoryId } of queueRuns(
		repository,
		commit,
		defaultBranch,
		api,
		requiredJobs,
	)) {
		const artifactPages = asArray(
			await api(`${root}/runs/${runId}/artifacts?per_page=100`),
			"artifact pages",
		);
		const artifacts = artifactPages.flatMap((page) =>
			asArray(asRecord(page, "artifact page").artifacts, "artifacts"),
		);
		const artifactId = serverBuildArtifact(artifacts, runId, commit, repositoryId);
		if (artifactId !== undefined) {
			return { runId, artifactId };
		}
	}
	return undefined;
}

/** `gh api`, with list endpoints read whole as one array of pages. */
export async function ghApi(path: string): Promise<unknown> {
	return parseJson(
		await output("gh", [
			"api",
			path,
			...(path.includes("/workflows/") ? [] : ["--paginate", "--slurp"]),
		]),
	);
}

if (import.meta.main) {
	const result = await resolveServerBuild(
		requiredEnv(process.env, "GITHUB_REPOSITORY"),
		requiredEnv(process.env, "GITHUB_SHA"),
		requiredEnv(process.env, "DEFAULT_BRANCH"),
		ghApi,
	);
	if (result) {
		await appendFile(
			requiredEnv(process.env, "GITHUB_OUTPUT"),
			`run-id=${result.runId}\nartifact-id=${result.artifactId}\n`,
		);
		await appendFile(
			requiredEnv(process.env, "GITHUB_STEP_SUMMARY"),
			`Reusing the packaged server validated by [merge-queue CI](${requiredEnv(process.env, "GITHUB_SERVER_URL")}/${requiredEnv(process.env, "GITHUB_REPOSITORY")}/actions/runs/${result.runId}) for this exact commit. All current-run validation and image publication remain enabled.\n`,
		);
	}
}
