/**
 * Writes `rename-only=true` to `$GITHUB_OUTPUT` when this pull request head differs from the last head
 * with a successful CI/CD run only in that `db/changelog/next/` files moved to a later name.
 * `vp run db:reorder` makes such a move. The pull request then runs the order guard and the migration-chain
 * replay instead of its whole fast tier, and the merge group still runs everything
 * (docs/contributor/ci-cd.mdx § Rename-only heads).
 *
 * The decision compares each head's own diff, from its merge base with the pull request's base, as
 * paths and blob ids from git. Every moved file keeps its slug and its exact bytes. A file outside
 * `next/` may change only by the renamed basenames, and the check derives that change from both
 * blobs. Anything else, or any failure to read the evidence, writes `rename-only=false`, and the full
 * fast tier runs.
 */
import { execFileSync } from "node:child_process";
import { appendFile } from "node:fs/promises";
import path from "node:path";

import { NEXT_CHANGELOG_DIRECTORY, migrationTime } from "./lib/changelog.ts";
import { requiredEnv } from "./lib/env.ts";
import { asArray, asRecord, asString } from "./lib/json.ts";
import { ghApi } from "./resolve-server-build.ts";

/** A head's own changes: each changed path and its blob id at that head (zeros for a deletion). */
export type OwnDiff = ReadonlyMap<string, string>;

export interface Verdict {
	readonly renameOnly: boolean;
	readonly reason: string;
	readonly renames: readonly { readonly from: string; readonly to: string }[];
}

const DELETED = "0".repeat(40);
const inNext = (file: string) => path.posix.dirname(file) === NEXT_CHANGELOG_DIRECTORY;
const slug = (file: string) => path.posix.basename(file).slice(15);
const validName = (file: string) => {
	try {
		migrationTime(file);
		return true;
	} catch {
		return false;
	}
};

const no = (reason: string): Verdict => ({ renameOnly: false, reason, renames: [] });

/** Whether `after` equals `before` except for `next/` files moved with identical bytes. */
export function renameOnly(
	before: OwnDiff,
	after: OwnDiff,
	read: (blob: string) => string,
): Verdict {
	const changed = [...new Set([...before.keys(), ...after.keys()])]
		.filter((file) => before.get(file) !== after.get(file))
		.toSorted();
	if (changed.length === 0) {
		return no("the own diff is unchanged");
	}
	const present = (diff: OwnDiff, file: string) => {
		const blob = diff.get(file);
		return blob !== undefined && blob !== DELETED;
	};
	const gone = changed.filter(
		(file) => inNext(file) && present(before, file) && !present(after, file),
	);
	const added = changed.filter(
		(file) => inNext(file) && present(after, file) && !present(before, file),
	);
	const renames: { from: string; to: string }[] = [];
	const taken = new Set<string>();
	for (const from of gone) {
		const to = added.find(
			(file) =>
				!taken.has(file) &&
				after.get(file) === before.get(from) &&
				slug(file) === slug(from) &&
				validName(file) &&
				file > from,
		);
		if (to === undefined) {
			return no(`${from} has no later file with the same slug and bytes`);
		}
		taken.add(to);
		renames.push({ from, to });
	}
	if (renames.length === 0) {
		return no("no migration moved");
	}
	for (const file of changed) {
		if (gone.includes(file) || taken.has(file)) {
			continue;
		}
		if (inNext(file) || !present(before, file) || !present(after, file)) {
			return no(`${file} changed beyond a rename`);
		}
		const old = read(before.get(file) ?? "");
		let derived = old;
		for (const rename of renames) {
			derived = derived.replaceAll(
				path.posix.basename(rename.from),
				path.posix.basename(rename.to),
			);
		}
		if (derived === old || derived !== read(after.get(file) ?? "")) {
			return no(`${file} changed beyond the renamed filenames`);
		}
	}
	return { renameOnly: true, reason: `${renames.length} migration(s) moved`, renames };
}

const git = (...args: string[]) =>
	execFileSync("git", args, { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });

/** The head's own diff from its merge base with `base`. */
export function ownDiff(head: string, base: string): Map<string, string> {
	const mergeBase = git("merge-base", head, base).trim();
	const diff = new Map<string, string>();
	for (const line of git("diff", "--no-renames", "--raw", "--abbrev=40", mergeBase, head).split(
		"\n",
	)) {
		const [meta, file] = line.split("\t");
		const blob = meta?.split(" ")[3];
		if (file !== undefined && blob !== undefined) {
			diff.set(file, blob);
		}
	}
	return diff;
}

/**
 * Whether a run passed the suites that a rename-only head skips: every numbered integration shard and
 * the database tests. A green run of a draft or a stack layer skipped them, so it proves nothing about
 * the migrations that the rename moves.
 */
export function passedSkippedSuites(
	jobs: readonly { readonly name: string; readonly conclusion: string | null }[],
): boolean {
	const shards = jobs.filter((job) =>
		/^Server tests \/ Integration \(\d+ of \d+\)$/u.test(job.name),
	);
	const count = Number(/ of (?<count>\d+)\)$/u.exec(shards[0]?.name ?? "")?.groups?.count ?? 0);
	const database = jobs.filter((job) => job.name === "Build / Database migrations");
	return (
		count > 0 &&
		shards.length === count &&
		database.length === 1 &&
		[...shards, ...database].every((job) => job.conclusion === "success")
	);
}

async function runJobs(repository: string, runId: number) {
	const pages = asArray(
		await ghApi(`repos/${repository}/actions/runs/${runId}/jobs?filter=latest&per_page=100`),
		"job pages",
	);
	return pages.flatMap((page) =>
		asArray(asRecord(page, "job page").jobs, "jobs").map((value) => {
			const job = asRecord(value, "job");
			return {
				name: asString(job.name, "job name"),
				conclusion: typeof job.conclusion === "string" ? job.conclusion : null,
			};
		}),
	);
}

/**
 * The newest other head of this same-repository pull request whose CI/CD run succeeded and passed the
 * suites that a rename-only head skips.
 */
async function previousHead(
	repository: string,
	pullRequest: number,
	branch: string,
	head: string,
	defaultBranch: string,
): Promise<string | undefined> {
	const listing = asRecord(
		await ghApi(
			`repos/${repository}/actions/workflows/cicd.yml/runs?event=pull_request&status=success&branch=${encodeURIComponent(branch)}&per_page=30`,
		),
		"workflow runs",
	);
	for (const value of asArray(listing.workflow_runs, "workflow runs")) {
		const run = asRecord(value, "workflow run");
		const sha = asString(run.head_sha, "head sha");
		const ours = asArray(run.pull_requests, "pull requests").some((entry) => {
			const pull = asRecord(entry, "pull request");
			return (
				pull.number === pullRequest &&
				asString(asRecord(pull.base, "base").ref, "base ref") === defaultBranch
			);
		});
		if (sha !== head && ours && passedSkippedSuites(await runJobs(repository, Number(run.id)))) {
			return sha;
		}
	}
	return undefined;
}

async function decide(): Promise<Verdict> {
	const head = requiredEnv(process.env, "HEAD_SHA");
	const base = requiredEnv(process.env, "BASE_SHA");
	const previous = await previousHead(
		requiredEnv(process.env, "GITHUB_REPOSITORY"),
		Number(requiredEnv(process.env, "PR_NUMBER")),
		requiredEnv(process.env, "HEAD_REF"),
		head,
		requiredEnv(process.env, "DEFAULT_BRANCH"),
	);
	if (previous === undefined) {
		return no(
			"no earlier head of this pull request has a successful CI/CD run that passed the integration shards and the database tests",
		);
	}
	git("fetch", "--no-tags", "--quiet", "origin", previous);
	const verdict = renameOnly(ownDiff(previous, base), ownDiff(head, base), (blob) =>
		git("cat-file", "blob", blob),
	);
	return { ...verdict, reason: `${verdict.reason} (compared with ${previous.slice(0, 10)})` };
}

if (import.meta.main) {
	let verdict: Verdict;
	try {
		verdict = await decide();
	} catch (error) {
		verdict = no(`the evidence could not be read: ${String(error).split("\n")[0] ?? ""}`);
	}
	process.stdout.write(`Rename-only head: ${verdict.renameOnly}. ${verdict.reason}\n`);
	for (const { from, to } of verdict.renames) {
		process.stdout.write(`  ${path.posix.basename(from)} -> ${path.posix.basename(to)}\n`);
	}
	await appendFile(
		requiredEnv(process.env, "GITHUB_OUTPUT"),
		`rename-only=${verdict.renameOnly}\n`,
	);
}
