import { appendFile } from "node:fs/promises";

import { requiredEnv } from "./lib/env.ts";
import { compareStatus } from "./lib/github.ts";
import { readJsonFile } from "./lib/json.ts";
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

if (import.meta.main) {
	const channel = requiredEnv(process.env, "CHANNEL");
	if (channel !== "Staging") {
		throw new Error("automatic promotion is only supported for Staging");
	}
	const commit = requiredEnv(process.env, "COMMIT");
	const repository = requiredEnv(process.env, "GITHUB_REPOSITORY");
	const current = parseChannel(await readJsonFile("deploy-state/channels/staging.json"));
	const apply = await automaticPromotion(current, commit, async (base, head) =>
		compareStatus(repository, base, head),
	);
	console.log(
		apply
			? `Promoting ${commit}`
			: "Automatic promotion skipped: channel is frozen or already at a newer build.",
	);
	await appendFile(requiredEnv(process.env, "GITHUB_OUTPUT"), `apply=${apply}\n`);
}
