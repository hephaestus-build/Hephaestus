/**
 * Resolves what a manual promotion moves an environment to and writes the channel that
 * `promote.yml` signs and publishes: a published release by tag, or a commit of the default branch
 * with the digests its build produced.
 */
import { appendFile, mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";

import { readInventory, resolveAndVerify, resolveImages } from "./commit-image-lock.ts";
import { requiredEnv } from "./lib/env.ts";
import { compareStatus } from "./lib/github.ts";
import { output } from "./lib/process.ts";
import { isCommit, RELEASE_TAG, serializeChannel, type Channel } from "./reconcile-deployment.ts";

export interface PromotionRequest {
	release?: string;
	commit?: string;
	allowRollback: boolean;
	freeze: boolean;
	refreshDatabaseImage: boolean;
}

export interface PromotionSources {
	/** Where `head` stands relative to `base`, as GitHub's compare API reports it. */
	compare: (base: string, head: string) => Promise<string>;
	isDraft: (release: string) => Promise<boolean>;
	/** The digests a commit's build produced, verified against that commit's provenance. */
	images: (commit: string) => Promise<Record<string, string>>;
}

export interface Promotion {
	channel: Channel;
	/** What the environment reports once it runs the channel: the version, or the commit. */
	version: string;
}

export async function resolvePromotion(
	request: PromotionRequest,
	sources: PromotionSources,
): Promise<Promotion> {
	const { release, commit, allowRollback, freeze, refreshDatabaseImage } = request;
	if (commit !== undefined) {
		if (release !== undefined) throw new Error("Name a release or a commit, not both");
		if (!isCommit(commit)) throw new Error(`Follow a full commit SHA, not '${commit}'`);
		// Dispatch permission must not authorize commits outside main.
		const status = await sources.compare(commit, "main");
		if (status !== "identical" && status !== "ahead")
			throw new Error(`${commit} is not on the default branch`);
		const images = await sources.images(commit);
		return {
			channel: { release: commit, images, allowRollback, freeze, refreshDatabaseImage },
			version: commit,
		};
	}
	if (release === undefined) throw new Error("Name a release or a commit");
	// A release runs the images its evidence describes, so there is no carried pin to refresh.
	if (refreshDatabaseImage)
		throw new Error("refresh-database-image applies to a commit, not to a release");
	if (!RELEASE_TAG.test(release))
		throw new Error(`Promote an immutable vX.Y.Z release, not '${release}'`);
	// The host binds the tag's source tree to the signed release lock before applying it.
	if (await sources.isDraft(release)) throw new Error(`${release} is still a draft`);
	return { channel: { release, allowRollback, freeze }, version: release.slice(1) };
}

if (import.meta.main) {
	const repository = requiredEnv(process.env, "GITHUB_REPOSITORY");
	const owner = repository.slice(0, repository.indexOf("/"));
	const environment = requiredEnv(process.env, "CHANNEL");
	const hostname = requiredEnv(process.env, "HOSTNAME");
	const optional = (name: string): string | undefined => {
		const value = process.env[name];
		// A dispatch input left blank arrives as an empty string.
		return value === "" ? undefined : value;
	};
	const { channel, version } = await resolvePromotion(
		{
			release: optional("RELEASE"),
			commit: optional("COMMIT"),
			allowRollback: process.env.ALLOW_ROLLBACK === "true",
			freeze: process.env.FREEZE === "true",
			refreshDatabaseImage: process.env.REFRESH_DATABASE_IMAGE === "true",
		},
		{
			compare: (base, head) => compareStatus(repository, base, head),
			isDraft: async (release) =>
				(
					await output("gh", [
						"release",
						"view",
						release,
						"--repo",
						repository,
						"--json",
						"isDraft",
						"--jq",
						".isDraft",
					])
				).trim() !== "false",
			// The inventory belongs to the commit being promoted; this checkout's may be newer than it.
			images: async (commit) =>
				resolveImages(
					await readInventory(".promotion-inventory/security/release-images.json"),
					commit,
					owner,
					resolveAndVerify,
				),
		},
	);
	const file = `channels/${environment.toLowerCase()}.json`;
	await mkdir(join("deploy-state", "channels"), { recursive: true });
	await writeFile(join("deploy-state", file), serializeChannel(channel));
	await appendFile(
		requiredEnv(process.env, "GITHUB_OUTPUT"),
		`environment_url=https://${hostname}\nchannel=${file}\nrelease=${channel.release}\nversion=${version}\n`,
	);
}
