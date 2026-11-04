/**
 * Moves this branch's unreleased migrations after the newest migration on the base branch.
 *
 *   vp run db:reorder [--base <revision>]          rename out-of-order files with `git mv`
 *   vp run db:reorder --check [--base <revision>]  exit 1 and name the files, change nothing
 *
 * The rename also sorts after every `next/` file that a pull request in the merge queue adds: that
 * pull request merges first, and the merge group applies its file before this branch's. The check
 * reads the queue too; the pre-push hook reports when it cannot.
 *
 * Liquibase reads `next/` in filename order, and a released file never moves
 * (`docs/contributor/database-migration.mdx` § Changelog immutability policy). So a branch file that
 * sorts at or before the base's newest file must take a later name before it merges. The rename keeps
 * the slug, the content and the relative order of the branch's files. The change set ids keep their
 * timestamps: Liquibase identifies a change set by id, author and file name together.
 */
import { execFileSync, spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import path from "node:path";
import { parseArgs } from "node:util";

import {
	CHANGELOG_DIRECTORY,
	NEXT_CHANGELOG_DIRECTORY,
	migrationFilename,
} from "./lib/changelog.ts";
import { environmentWithoutGitRepository } from "./lib/git-environment.ts";
import { asArray, asRecord, asString, parseJson } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

export interface Rename {
	readonly from: string;
	readonly to: string;
}

const slugOf = (file: string): string => path.posix.basename(file).slice(15, -".xml".length);

/**
 * The renames that put every branch file after the newest file of the base and of the merge queue,
 * in the branch's own order. Empty when the branch's earliest file already sorts last, so a second
 * run changes nothing.
 */
export function reorder(
	base: readonly string[],
	branch: readonly string[],
	now: number,
	queued: readonly string[] = [],
): Rename[] {
	const own = new Set(branch);
	const newest = [...base, ...queued.filter((file) => !own.has(file))]
		.map((file) => path.posix.basename(file))
		.toSorted()
		.at(-1);
	const mine = branch.toSorted();
	const first = mine[0];
	if (newest === undefined || first === undefined || path.posix.basename(first) > newest) {
		return [];
	}
	const taken = [newest];
	return mine.map((from) => {
		const name = migrationFilename(slugOf(from), taken, now);
		taken.push(name);
		return { from, to: `${path.posix.dirname(from)}/${name}` };
	});
}

/** The `next/` names that one commit of `QUEUE_QUERY` lists; none when it has no such directory. */
function nextFilesAt(commit: unknown): string[] | undefined {
	if (commit === null || commit === undefined) {
		return undefined;
	}
	const { file } = asRecord(commit, "commit");
	if (file === null || file === undefined) {
		return [];
	}
	const tree = asRecord(asRecord(file, "file").object, "object");
	return asArray(tree.entries, "entries")
		.map((node) => asString(asRecord(node, "entry").name, "name"))
		.filter((name) => name.endsWith(".xml"))
		.map((name) => `${NEXT_CHANGELOG_DIRECTORY}/${name}`);
}

/**
 * The `next/` files of each entry in `main`'s merge queue, from the response to `QUEUE_QUERY`: at the
 * entry's merge-group commit, which holds `main` and every entry ahead, or before the queue builds one,
 * at the pull request's head. A directory listing holds every migration, however many files the pull
 * request changes, and also the base's, which sort no later.
 */
export function queuedMigrations(response: unknown): string[] {
	const data = asRecord(asRecord(response, "response").data, "data");
	const queue = asRecord(data.repository, "repository").mergeQueue;
	if (queue === null || queue === undefined) {
		return [];
	}
	const entries = asRecord(asRecord(queue, "mergeQueue").entries, "entries");
	return asArray(entries.nodes, "entries").flatMap((node) => {
		const entry = asRecord(node, "entry");
		const pullRequest = asRecord(entry.pullRequest, "pullRequest");
		const { headRef } = pullRequest;
		const branchTip =
			headRef === null || headRef === undefined ? undefined : asRecord(headRef, "headRef").target;
		// A deleted head branch leaves only the commit list, whose last commit is the head.
		const lastCommit = asArray(asRecord(pullRequest.commits, "commits").nodes, "commits").at(-1);
		const files =
			nextFilesAt(entry.headCommit) ??
			nextFilesAt(branchTip) ??
			nextFilesAt(
				lastCommit === undefined ? undefined : asRecord(lastCommit, "commit node").commit,
			);
		if (files === undefined) {
			throw new Error(
				`Queued pull request #${String(pullRequest.number)} has no commit to read its migrations from. Check them by hand.`,
			);
		}
		return files;
	});
}

const NEXT_FILES = "file(path:$path){object{... on Tree{entries{name}}}}";

const QUEUE_QUERY = `query($owner:String!,$name:String!,$path:String!){repository(owner:$owner,name:$name){mergeQueue(branch:"main"){entries(first:100){nodes{headCommit{${NEXT_FILES}} pullRequest{number headRef{target{... on Commit{${NEXT_FILES}}}} commits(last:1){nodes{commit{${NEXT_FILES}}}}}}}}}}`;

/** The `next/` files that the merge queue of a GitHub origin adds; none for any other remote. */
export function readQueue(cwd: string): string[] {
	const origin = spawnSync("git", ["remote", "get-url", "origin"], {
		cwd,
		encoding: "utf8",
		env: environmentWithoutGitRepository(),
	});
	const repository = githubRepository(origin.status === 0 ? origin.stdout.trim() : "");
	if (repository === undefined) {
		return [];
	}
	const [owner = "", name = ""] = repository.split("/");
	const result = spawnSync(
		"gh",
		[
			"api",
			"graphql",
			"-f",
			`query=${QUEUE_QUERY}`,
			"-f",
			`owner=${owner}`,
			"-f",
			`name=${name}`,
			"-f",
			`path=${NEXT_CHANGELOG_DIRECTORY}`,
		],
		{ cwd, encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES },
	);
	if (result.status !== 0) {
		throw new Error(`Could not read the merge queue of ${repository}: ${result.stderr.trim()}`);
	}
	return queuedMigrations(parseJson(result.stdout));
}

/** The renames that would move a file that `main` or a release already has: never allowed. */
export function refusals(renames: readonly Rename[], released: ReadonlySet<string>): string[] {
	return renames.filter(({ from }) => released.has(from)).map(({ from }) => from);
}

function git(cwd: string, ...args: string[]): string[] {
	const stdout = execFileSync("git", args, {
		cwd,
		encoding: "utf8",
		maxBuffer: CAPTURE_LIMIT_BYTES,
		env: environmentWithoutGitRepository(),
	});
	return stdout.split("\n").filter(Boolean);
}

const changelogsAt = (cwd: string, revision: string, directory: string): string[] =>
	git(cwd, "ls-tree", "-r", "--name-only", `${revision}^{tree}`, "--", `${directory}/`);

/** The base's `next/` files, and the tracked or untracked ones the base does not have. */
export function migrations(
	base: string,
	cwd = process.cwd(),
): { base: string[]; branch: string[] } {
	const onBase = changelogsAt(cwd, base, NEXT_CHANGELOG_DIRECTORY);
	const known = new Set(onBase);
	const here = [
		...git(cwd, "ls-files", "--", `${NEXT_CHANGELOG_DIRECTORY}/`),
		...git(cwd, "ls-files", "--others", "--exclude-standard", "--", `${NEXT_CHANGELOG_DIRECTORY}/`),
	];
	return {
		base: onBase.filter((file) => file.endsWith(".xml")),
		// A file deleted from the worktree but not yet from the index is not this branch's migration.
		branch: [...new Set(here)].filter(
			(file) => file.endsWith(".xml") && !known.has(file) && existsSync(path.join(cwd, file)),
		),
	};
}

/** Every changelog file on `base`, on `origin/main` or in a release tag. */
function releasedFiles(cwd: string, base: string): Set<string> {
	const revisions = [base, "origin/main", ...git(cwd, "tag", "--list", "v*")].filter(
		(revision) =>
			spawnSync("git", ["rev-parse", "--verify", "--quiet", `${revision}^{commit}`], {
				cwd,
				env: environmentWithoutGitRepository(),
			}).status === 0,
	);
	return new Set(revisions.flatMap((revision) => changelogsAt(cwd, revision, CHANGELOG_DIRECTORY)));
}

/**
 * Stages the renames that put this branch's migrations after `base`, and returns them. Throws,
 * changing nothing, when a rename would move a file that the base, main or a release has.
 */
export function reorderMigrations(
	base: string,
	cwd = process.cwd(),
	queued: readonly string[] = [],
): Rename[] {
	const found = migrations(base, cwd);
	const renames = reorder(found.base, found.branch, Date.now(), queued);
	if (renames.length === 0) {
		return [];
	}
	const refused = refusals(renames, releasedFiles(cwd, base));
	if (refused.length > 0) {
		throw new Error(
			`Refusing to rename migrations that main or a release already has:\n${refused.join("\n")}\nRebase onto origin/main, or pass the base this branch starts from.`,
		);
	}
	const taken = renames.filter(({ to }) => existsSync(path.join(cwd, to)));
	if (taken.length > 0) {
		throw new Error(`These names already exist: ${taken.map(({ to }) => to).join(", ")}`);
	}
	for (const { from, to } of renames) {
		if (git(cwd, "ls-files", "--", from).length === 0) {
			git(cwd, "add", "--", from);
		}
		git(cwd, "mv", "--", from, to);
	}
	return renames;
}

/** The message for a branch whose migrations sort too early, or undefined when they are in order. */
export function orderProblem(
	base: string,
	cwd = process.cwd(),
	queued: readonly string[] = [],
): string | undefined {
	const found = migrations(base, cwd);
	const renames = reorder(found.base, found.branch, Date.now(), queued);
	if (renames.length === 0) {
		return undefined;
	}
	const own = new Set(found.branch);
	// A queued head also lists the base's files; only the others are what the queue adds.
	const ahead = queued.filter((file) => !own.has(file) && !found.base.includes(file));
	const newest =
		[...found.base, ...ahead]
			.toSorted((a, b) => path.posix.basename(a).localeCompare(path.posix.basename(b)))
			.at(-1) ?? "";
	const where = ahead.includes(newest)
		? "the newest that the merge queue adds"
		: `the newest on ${base}`;
	return [
		`These migrations sort at or before ${path.posix.basename(newest)}, ${where}:`,
		...renames.map(({ from }) => `  ${path.posix.basename(from)}`),
		"The merge queue rejects them. Run `vp run db:reorder`, commit the rename, and push.",
	].join("\n");
}

/** The GitHub repository that a remote URL names, or undefined for any other remote. */
export function githubRepository(url: string): string | undefined {
	return /^(?:https:\/\/github\.com\/|git@github\.com:)(?<name>[^/]+\/[^/]+?)(?:\.git)?$/u.exec(url)
		?.groups?.name;
}

/**
 * A fork's `main` and tags can lag the main repository, so main's newest migrations would look like
 * this branch's own. Only a clone of the main repository renames.
 */
function refuseFork(cwd: string): void {
	const repository = githubRepository(git(cwd, "remote", "get-url", "origin")[0] ?? "");
	if (repository === undefined) {
		return;
	}
	const fork = spawnSync(
		"gh",
		["repo", "view", repository, "--json", "isFork", "--jq", ".isFork"],
		{
			cwd,
			encoding: "utf8",
		},
	);
	if (fork.status !== 0 || fork.stdout.trim() !== "false") {
		throw new Error(
			`Cannot confirm that origin (${repository}) is the main repository. On a fork, ask a maintainer to reorder, or rename the file by hand after main's newest.`,
		);
	}
}

function main(): void {
	const { values } = parseArgs({
		options: { base: { type: "string", default: "origin/main" }, check: { type: "boolean" } },
	});
	const { base } = values;
	const cwd = git(process.cwd(), "rev-parse", "--show-toplevel")[0] ?? process.cwd();
	if (values.check === true) {
		const problem = orderProblem(base, cwd, readQueue(cwd));
		if (problem !== undefined) {
			console.error(problem);
			process.exitCode = 1;
			return;
		}
		console.log(`This branch's migrations sort after ${base} and the merge queue.`);
		return;
	}
	refuseFork(cwd);
	// A stale origin/main would make a file that main already has look like this branch's own.
	const fetched = spawnSync("git", ["fetch", "--quiet", "--tags", "origin", "main"], {
		cwd,
		stdio: "inherit",
		env: environmentWithoutGitRepository(),
	});
	if (fetched.status !== 0) {
		throw new Error("Could not fetch origin main and its tags. Fetch them, then run this again.");
	}
	const renames = reorderMigrations(base, cwd, readQueue(cwd));
	if (renames.length === 0) {
		console.log(
			`This branch's migrations already sort after ${base} and the merge queue; nothing to rename.`,
		);
		return;
	}
	for (const { from, to } of renames) {
		console.log(`${path.posix.basename(from)} -> ${path.posix.basename(to)}`);
	}
	console.log(
		"Staged the rename. Commit it. A local database that applied the old name needs `vp run dev:reset`.",
	);
}

if (import.meta.main) {
	try {
		main();
	} catch (error) {
		console.error(error instanceof Error ? error.message : String(error));
		process.exitCode = 1;
	}
}
