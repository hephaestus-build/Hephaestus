import { appendFile } from "node:fs/promises";

import { requiredEnv } from "./lib/env.ts";
import { compareStatus } from "./lib/github.ts";
import { asArray, asRecord, isRecord, parseJson, readJsonFile } from "./lib/json.ts";
import { output } from "./lib/process.ts";
import { isCommit, parseChannel, type Channel } from "./reconcile-deployment.ts";

/**
 * What an automatic request does. `held`: the channel is frozen, so it stays on its release until a
 * promotion without freeze. `current`: the channel already carries this build or a later one.
 * Neither publishes anything, and neither is a failure.
 */
export type AutomaticDecision =
	| { outcome: "promote" }
	| { outcome: "held"; release: string }
	| { outcome: "current"; release: string };

/** Called under the promotion concurrency lock, against its freshly checked-out channel. */
export async function automaticPromotion(
	channel: Channel,
	commit: string,
	compare: (base: string, head: string) => Promise<string>,
): Promise<AutomaticDecision> {
	if (!isCommit(commit)) {
		throw new Error("automatic promotion requires a full commit SHA");
	}
	if (channel.freeze === true) {
		return { outcome: "held", release: channel.release };
	}
	const status = await compare(channel.release, commit);
	if (status === "ahead") {
		return { outcome: "promote" };
	}
	if (status === "behind" || status === "identical") {
		return { outcome: "current", release: channel.release };
	}
	throw new Error(`cannot automatically promote divergent or unknown history: ${status}`);
}

/** A hold older than this alerts: Release / Host watch fails, and its failure reaches the maintainers. */
export const freezeAlertHours = 24;

/**
 * When a channel file last changed on deploy-state. Automatic promotion leaves a frozen channel alone,
 * so for a frozen channel this is when the hold began. Undefined when the history cannot be read.
 */
export async function channelChangedAt(
	listCommits: () => Promise<unknown>,
): Promise<Date | undefined> {
	try {
		const [latest] = asArray(await listCommits(), "channel commits");
		const { date } = asRecord(
			asRecord(asRecord(latest, "commit").commit, "commit").committer,
			"committer",
		);
		const changed = typeof date === "string" ? new Date(date) : undefined;
		return changed !== undefined && !Number.isNaN(changed.getTime()) ? changed : undefined;
	} catch {
		return undefined;
	}
}

/** The deploy-state history of one channel file, newest first. */
export const channelCommits = (repository: string, file: string) => async () =>
	parseJson(
		await output("gh", [
			"api",
			`repos/${repository}/commits?sha=deploy-state&path=${file}&per_page=1`,
		]),
	);

/** How long a hold has lasted, in whole hours, or undefined without a start. */
export const heldHours = (since: Date | undefined, now: Date) =>
	since === undefined ? undefined : Math.floor((now.getTime() - since.getTime()) / 3_600_000);

/** The run's summary of a request that leaves the channel where it is. */
export function unchangedSummary(
	decision: Exclude<AutomaticDecision, { outcome: "promote" }>,
	channel: string,
	commit: string,
	heldSince?: Date,
	now = new Date(),
): { title: string; text: string } {
	const build = commit.slice(0, 10);
	if (decision.outcome === "current") {
		return {
			title: `${channel} already carries this build`,
			text: `The ${channel} channel is on ${decision.release}, which already includes ${build}. Nothing was published or deployed.`,
		};
	}
	const hours = heldHours(heldSince, now);
	const since =
		heldSince === undefined || hours === undefined
			? "since a time that deploy-state did not report"
			: `since ${heldSince.toISOString()} (${hours} h)`;
	return {
		title: `${channel} is held`,
		text: `The ${channel} channel is frozen on ${decision.release} ${since}, so ${build} was not promoted. Nothing was published or deployed. A promotion without freeze lifts the hold.`,
	};
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
	const decision = await automaticPromotion(current, commit, compare);
	const ahead = decision.outcome === "promote";
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
	if (decision.outcome !== "promote") {
		const heldSince =
			decision.outcome === "held"
				? await channelChangedAt(channelCommits(repository, "channels/staging.json"))
				: undefined;
		const { title, text } = unchangedSummary(decision, channel, commit, heldSince);
		// A hold is a state, not a failure, but it must stand out: continuous staging changes nothing.
		console.log(`::${decision.outcome === "held" ? "warning" : "notice"} title=${title}::${text}`);
		const summary = process.env.GITHUB_STEP_SUMMARY;
		if (summary !== undefined && summary !== "") {
			await appendFile(summary, `### ${title}\n\n${text}\n`);
		}
	} else if (superseding === undefined) {
		console.log(`Promoting ${commit}`);
	} else {
		console.log(
			`Promoting ${superseding}, which already passed CI/CD and descends from ${commit}.`,
		);
	}
	await appendFile(
		requiredEnv(process.env, "GITHUB_OUTPUT"),
		ahead
			? `apply=true\noutcome=promote\ncommit=${target}\n`
			: `apply=false\noutcome=${decision.outcome}\n`,
	);
}
