/**
 * Decides whether a pull request is a *verified revert*.
 *
 * The changeset freeze rules — MIGRATION.md is not editable, pending changesets and migration
 * fragments are not deletable — describe how a feature moves forward. A revert of a release
 * commit necessarily restores every one of them, so it can never satisfy the guard, and the three
 * release reverts so far each needed an administrator bypass. A guard that has to be bypassed on a
 * known-good path teaches people to bypass it.
 *
 * The exemption is deliberately structural rather than lexical: the pull request title and branch
 * name are never read, because both are attacker-chosen. A pull request qualifies only when *every*
 * non-merge commit it adds over the base
 *
 *   1. carries exactly one `This reverts commit <sha>.` line, the trailer `git revert` writes,
 *   2. names a commit that exists and is an ancestor of the base — already reviewed and merged,
 *   3. names a commit with a single parent — reverting a merge is out of scope, and
 *   4. has a patch identical to that commit's patch reversed, compared by `git patch-id --stable`
 *      over a `--binary` diff, so neither an extra hunk nor an altered binary payload can ride
 *      along.
 *
 * Anything else — an unrelated commit in the range, a fabricated trailer, a conflict-resolved
 * revert whose patch no longer matches — leaves the guard fully in force. The check fails closed.
 */

import { execFileSync } from "node:child_process";
import { appendFileSync } from "node:fs";
import { isSet } from "./lib/env.ts";

/** `git revert` writes exactly this line; a revert of a merge adds `, reversing changes …`. */
const REVERT_TRAILER = /^This reverts commit (?<sha>[0-9a-f]{7,40})\.$/gmu;

const MAX_BUFFER = 64 * 1024 * 1024;

export interface RevertedCommit {
	/** A commit the pull request adds. */
	readonly commit: string;
	/** The already-merged commit it undoes. */
	readonly reverted: string;
}

export type RevertVerdict =
	| { readonly verified: true; readonly commits: readonly RevertedCommit[] }
	| { readonly verified: false; readonly reason: string };

const short = (sha: string): string => sha.slice(0, 8);

export function verifyRevert(baseSha: string, head = "HEAD", cwd?: string): RevertVerdict {
	// Git hooks export GIT_DIR and GIT_INDEX_FILE, which would silently redirect every command
	// below at whichever repository invoked the hook.
	const env = Object.fromEntries(
		Object.entries(process.env).filter(([key]) => !key.startsWith("GIT_")),
	);
	const git = (...args: string[]): string =>
		execFileSync("git", args, {
			cwd,
			env,
			encoding: "utf8",
			maxBuffer: MAX_BUFFER,
			stdio: ["ignore", "pipe", "pipe"],
		}).trim();
	const succeeds = (...args: string[]): boolean => {
		try {
			git(...args);
			return true;
		} catch {
			return false;
		}
	};
	const commit = (revision: string): string | undefined => {
		try {
			return git("rev-parse", "--verify", "--end-of-options", `${revision}^{commit}`);
		} catch {
			return undefined;
		}
	};
	/** The identity of the change between two commits, independent of where it applies. */
	const patchId = (from: string, to: string): string => {
		const diff = execFileSync("git", ["diff", "--binary", from, to], {
			cwd,
			env,
			maxBuffer: MAX_BUFFER,
			stdio: ["ignore", "pipe", "pipe"],
		});
		const identity = execFileSync("git", ["patch-id", "--stable"], {
			cwd,
			env,
			input: diff,
			encoding: "utf8",
			maxBuffer: MAX_BUFFER,
			stdio: ["pipe", "pipe", "pipe"],
		});
		return identity.trim().split(" ")[0] ?? "";
	};

	const base = commit(baseSha);
	if (!isSet(base)) {
		return { verified: false, reason: `base ${baseSha} is not a commit in this clone` };
	}
	const tip = commit(head);
	if (!isSet(tip)) {
		return { verified: false, reason: `head ${head} is not a commit in this clone` };
	}

	// Merge commits carry no patch of their own; a branch that merged the base back in adds one.
	const added = git("rev-list", "--no-merges", `${base}..${tip}`).split("\n").filter(Boolean);
	if (added.length === 0) {
		return { verified: false, reason: "adds no commit over the base" };
	}

	const commits: RevertedCommit[] = [];
	for (const candidate of added) {
		const message = git("log", "-1", "--format=%B", candidate);
		const trailers = [...message.matchAll(REVERT_TRAILER)];
		const trailer = trailers[0]?.groups?.sha;
		if (trailers.length !== 1 || trailer === undefined) {
			return {
				verified: false,
				reason: `${short(candidate)} does not record exactly one "This reverts commit <sha>." line`,
			};
		}
		const reverted = commit(trailer);
		if (!isSet(reverted)) {
			return { verified: false, reason: `${short(candidate)} reverts unknown commit ${trailer}` };
		}
		if (!succeeds("merge-base", "--is-ancestor", reverted, base)) {
			return {
				verified: false,
				reason: `${short(candidate)} reverts ${short(reverted)}, which is not an ancestor of the base`,
			};
		}
		const parents = git("rev-list", "--parents", "-n", "1", reverted).split(" ").length - 1;
		if (parents !== 1) {
			return {
				verified: false,
				reason: `${short(candidate)} reverts ${short(reverted)}, which has ${parents} parents`,
			};
		}
		const applied = patchId(`${candidate}^`, candidate);
		const inverse = patchId(reverted, `${reverted}^`);
		if (applied === "" || applied !== inverse) {
			return {
				verified: false,
				reason: `${short(candidate)} is not the exact inverse of ${short(reverted)}`,
			};
		}
		commits.push({ commit: candidate, reverted });
	}
	return { verified: true, commits };
}

if (import.meta.main) {
	const [baseSha, head] = process.argv.slice(2);
	if (isSet(baseSha)) {
		const verdict = verifyRevert(baseSha, head);
		if (verdict.verified) {
			const reverted = verdict.commits.map((entry) => short(entry.reverted)).join(", ");
			console.log(
				`::notice::Verified revert of ${reverted}; the changeset freeze rules do not apply.`,
			);
		} else {
			console.log(`Not a verified revert (${verdict.reason}); the changeset rules apply in full.`);
		}
		const githubOutput = process.env.GITHUB_OUTPUT;
		if (isSet(githubOutput)) {
			appendFileSync(githubOutput, `verified-revert=${verdict.verified}\n`);
		}
	} else {
		console.log("::error::usage: verify-revert.ts <base-sha> [head]");
		process.exitCode = 1;
	}
}
