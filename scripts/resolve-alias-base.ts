/**
 * The commit whose published images a run aliases for every component its diff left alone.
 *
 * A run based on the default branch aliases from its own base, whose images that branch's own run
 * published. A layer of a stacked pull request is based on another branch's head, which published
 * nothing and belongs to no merged pull request, so the chain of bases is walked until a commit the
 * default branch contains. A run that reaches none has nothing to alias and builds every image
 * itself.
 */
import { appendFile } from "node:fs/promises";

import { requiredEnv } from "./lib/env.ts";
import { compareStatus } from "./lib/github.ts";
import { asArray, asRecord, asString, parseJson } from "./lib/json.ts";
import { output } from "./lib/process.ts";

export interface BaseChain {
	/** Where `head` stands relative to `base`, as GitHub's compare API reports it. */
	compare: (base: string, head: string) => Promise<string>;
	/** The base of the open pull request whose head is `commit`, when a pull request has that head. */
	baseOf: (commit: string) => Promise<string | undefined>;
}

/** A stack deeper than this, or a chain of bases that loops, is given up on rather than followed. */
const LAYER_LIMIT = 10;

export async function resolveAliasBase(
	base: string,
	defaultBranch: string,
	chain: BaseChain,
): Promise<string | undefined> {
	let commit: string | undefined = base;
	for (let layer = 0; commit !== undefined && layer < LAYER_LIMIT; layer += 1) {
		const status = await chain.compare(commit, defaultBranch);
		if (status === "identical" || status === "ahead") return commit;
		commit = await chain.baseOf(commit);
	}
	return undefined;
}

if (import.meta.main) {
	const repository = requiredEnv(process.env, "GITHUB_REPOSITORY");
	const commit = await resolveAliasBase(
		requiredEnv(process.env, "BASE_SHA"),
		requiredEnv(process.env, "DEFAULT_BRANCH"),
		{
			compare: (base, head) => compareStatus(repository, base, head),
			// A commit the default branch does not contain answers with the open pull requests whose
			// branches carry it; only the one it is the head of names the next base up.
			baseOf: async (head) => {
				const pulls = asArray(
					parseJson(await output("gh", ["api", `repos/${repository}/commits/${head}/pulls`])),
					"pull requests",
				);
				for (const [index, value] of pulls.entries()) {
					const pull = asRecord(value, `pull requests[${index}]`);
					const sha = asString(asRecord(pull.head, "pull request head").sha, "head.sha");
					if (sha === head)
						return asString(asRecord(pull.base, "pull request base").sha, "base.sha");
				}
				return undefined;
			},
		},
	);
	await appendFile(requiredEnv(process.env, "GITHUB_OUTPUT"), `commit=${commit ?? ""}\n`);
}
