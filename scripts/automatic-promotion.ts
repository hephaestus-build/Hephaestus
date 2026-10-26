import { appendFile } from "node:fs/promises";

import { requiredEnv } from "./lib/env.ts";
import { compareStatus } from "./lib/github.ts";
import { asArray, asRecord, isRecord, parseJson, readJsonFile } from "./lib/json.ts";
import { output } from "./lib/process.ts";
import { isCommit, parseChannel, type Channel } from "./reconcile-deployment.ts";

/** Called under the promotion concurrency lock, against its freshly checked-out channel. */
export async function automaticPromotion(
	channel: Channel,
	commit: string,
	compare: (base: string, head: string) => Promise<string>,
): Promise<boolean> {
	if (!isCommit(commit)) {
		throw new Error("automatic promotion requires a full commit SHA");
	}
	if (channel.freeze === true) {
		return false;
	}
	const status = await compare(channel.release, commit);
	if (status === "ahead") {
		return true;
	}
	if (status === "behind" || status === "identical") {
		return false;
	}
	throw new Error(`cannot automatically promote divergent or unknown history: ${status}`);
}

/** The head commit of a listed run, if it is a successful CI/CD build of a push to this repository's main. */
function qualifiedMainBuild(run: unknown, repository: string): string | undefined {
	if (
		!isRecord(run) ||
		run.event !== "push" ||
		run.status !== "completed" ||
		run.conclusion !== "success" ||
		run.head_branch !== "main" ||
		run.path !== ".github/workflows/cicd.yml" ||
		!isRecord(run.repository) ||
		run.repository.full_name !== repository ||
		!isRecord(run.head_repository) ||
		run.head_repository.full_name !== repository ||
		typeof run.head_sha !== "string" ||
		!isCommit(run.head_sha)
	) {
		return undefined;
	}
	return run.head_sha;
}

/**
 * The latest successful main build, if it strictly descends from `commit`, is promoted in place of
 * `commit`. The lookup is optional: when the listing or a comparison fails, the request stands.
 */
export async function supersedingBuild(
	repository: string,
	commit: string,
	listRuns: () => Promise<unknown>,
	compare: (base: string, head: string) => Promise<string>,
): Promise<string | undefined> {
	try {
		const runs = asArray(
			asRecord(await listRuns(), "workflow runs").workflow_runs,
			"workflow runs",
		);
		const candidate = qualifiedMainBuild(runs[0], repository);
		if (
			candidate !== undefined &&
			candidate !== commit &&
			(await compare(commit, candidate)) === "ahead"
		) {
			return candidate;
		}
	} catch (error) {
		console.log(
			`Promoting the requested build: no later build could be checked (${error instanceof Error ? error.message : String(error)}).`,
		);
	}
	return undefined;
}

if (import.meta.main) {
	const channel = requiredEnv(process.env, "CHANNEL");
	if (channel !== "Staging") {
		throw new Error("automatic promotion is only supported for Staging");
	}
	const commit = requiredEnv(process.env, "COMMIT");
	const repository = requiredEnv(process.env, "GITHUB_REPOSITORY");
	const current = parseChannel(await readJsonFile("deploy-state/channels/staging.json"));
	const compare = async (base: string, head: string) => compareStatus(repository, base, head);
	const ahead = await automaticPromotion(current, commit, compare);
	// Only the latest successful main build is checked; an inconclusive lookup leaves the request in place.
	const superseding = ahead
		? await supersedingBuild(
				repository,
				commit,
				async () =>
					parseJson(
						await output("gh", [
							"api",
							`repos/${repository}/actions/workflows/cicd.yml/runs?event=push&branch=main&status=success&per_page=1`,
						]),
					),
				compare,
			)
		: undefined;
	// Runs on main finish in any order, and the promotion lock keeps one pending request: a request
	// for an older build can replace the newer build's. It promotes the newer build, which descends
	// from it and is therefore ahead of the channel too, so no build is left unpromoted.
	const target = superseding ?? commit;
	if (!ahead) {
		console.log("Automatic promotion skipped: channel is frozen or already at a newer build.");
	} else if (superseding === undefined) {
		console.log(`Promoting ${commit}`);
	} else {
		console.log(
			`Promoting ${superseding}, which already passed CI/CD and descends from ${commit}.`,
		);
	}
	await appendFile(
		requiredEnv(process.env, "GITHUB_OUTPUT"),
		ahead ? `apply=true\ncommit=${target}\n` : "apply=false\n",
	);
}
