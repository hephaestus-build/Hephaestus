/**
 * Maintains the Version PR from the newest `main` commit whose push CI/CD succeeded.
 * `version-pr.yml` runs it from main's tip; docs/contributor/ci-cd.mdx § Merge policy owns the contract.
 */
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";

import { commitInput, createCommit, stageChanges } from "./commit-via-api.ts";
import { requiredEnv } from "./lib/env.ts";
import { asArray, asRecord, asString, parseJson } from "./lib/json.ts";
import { output, run, succeeds } from "./lib/process.ts";

export const VERSION_BRANCH = "changeset-release/main";
// The commit is made on this branch first. Resetting the Version PR's branch to a main commit
// before the commit exists would leave its head inside main, and GitHub marks such a PR merged.
const STAGING_BRANCH = "version-pr-staging/main";
const TITLE = "chore(release): version packages";
// GitHub refuses a pull request body above 65,536 characters.
const BODY_LIMIT = 60_000;

/** The newest first-parent commit of main that a successful push run validated. */
export function newestValidated(
	firstParents: readonly string[],
	validated: ReadonlySet<string>,
): string | undefined {
	return firstParents.find((sha) => validated.has(sha));
}

export interface MaintenanceState {
	readonly base: string;
	readonly baseVersion: string;
	readonly tipVersion: string;
	/** The main commit that the open Version PR versions, if one is open. */
	readonly versionedBase: string | undefined;
	/** The base is an ancestor of `versionedBase`, so versioning it would drop changesets. */
	readonly baseIsBehind: boolean;
	readonly pendingChangesets: boolean;
}

/** Why maintenance leaves the Version PR as it is, or `undefined` to version the base. */
export function skipReason(state: MaintenanceState): string | undefined {
	if (state.baseVersion !== state.tipVersion) {
		// The base predates a Version PR merge; its changesets are already released.
		return `main released ${state.tipVersion} after ${state.base}; a validated commit after the release maintains the Version PR`;
	}
	if (state.versionedBase === state.base) {
		return `the Version PR already versions ${state.base}`;
	}
	if (state.baseIsBehind) {
		return `the Version PR versions a newer validated commit than ${state.base}`;
	}
	if (!state.pendingChangesets) {
		return `${state.base} has no pending changesets`;
	}
	return undefined;
}

/** The title and body of the Version PR for the versioned tree. */
export function versionPullRequest(
	changelog: string,
	version: string,
	prerelease: string | undefined,
): { title: string; body: string } {
	const heading = `## ${version}`;
	const start = changelog.split("\n").indexOf(heading);
	if (start === -1) {
		throw new Error(`CHANGELOG.md has no ${heading} entry`);
	}
	const lines = changelog.split("\n").slice(start + 1);
	const end = lines.findIndex((line) => line.startsWith("## "));
	const notes = (end === -1 ? lines : lines.slice(0, end)).join("\n").trim();
	const header = `Merging this pull request releases \`${version}\`. \`version-pr.yml\` maintains it from the newest \`main\` commit whose CI/CD succeeded.\n\n# Releases\n## hephaestus@${version}\n\n`;
	const truncated = "\n\nThe notes continue in this pull request's `CHANGELOG.md`.\n";
	const room = BODY_LIMIT - header.length - truncated.length;
	return {
		title: prerelease === undefined ? TITLE : `${TITLE} (${prerelease})`,
		body:
			notes.length <= room ? `${header}${notes}\n` : `${header}${notes.slice(0, room)}${truncated}`,
	};
}

const gh = async (args: string[], input?: string) => output("gh", ["api", ...args], { input });

async function validatedShas(repository: string): Promise<Set<string>> {
	const response = asRecord(
		parseJson(
			await gh([
				`repos/${repository}/actions/workflows/cicd.yml/runs?branch=main&event=push&status=success&per_page=100`,
			]),
		),
		"workflow runs",
	);
	return new Set(
		asArray(response.workflow_runs, "workflow runs")
			.map((value) => asRecord(value, "workflow run"))
			.filter(
				(item) =>
					asString(asRecord(item.head_repository, "head repository").full_name, "repository") ===
					repository,
			)
			.map((item) => asString(item.head_sha, "head sha")),
	);
}

async function branchHead(branch: string): Promise<string | undefined> {
	const found = await output("git", ["ls-remote", "origin", `refs/heads/${branch}`]);
	const [sha = ""] = found.split("\t");
	return sha === "" ? undefined : sha;
}

async function pointBranch(repository: string, branch: string, sha: string): Promise<void> {
	const exists = (await branchHead(branch)) !== undefined;
	await (exists
		? gh(
				["--method", "PATCH", `repos/${repository}/git/refs/heads/${branch}`, "--input", "-"],
				JSON.stringify({ sha, force: true }),
			)
		: gh(
				["--method", "POST", `repos/${repository}/git/refs`, "--input", "-"],
				JSON.stringify({ ref: `refs/heads/${branch}`, sha }),
			));
}

async function packageVersion(revision: string): Promise<string> {
	const manifest = asRecord(
		parseJson(await output("git", ["show", `${revision}:package.json`])),
		"package.json",
	);
	return asString(manifest.version, "version");
}

async function prereleaseTag(revision: string): Promise<string | undefined> {
	if (!(await succeeds("git", ["cat-file", "-e", `${revision}:.changeset/pre.json`]))) {
		return undefined;
	}
	const state = asRecord(
		parseJson(await output("git", ["show", `${revision}:.changeset/pre.json`])),
		"pre state",
	);
	return state.mode === "pre" ? asString(state.tag, "pre tag") : undefined;
}

async function hasChangesets(revision: string): Promise<boolean> {
	const files = await output("git", ["ls-tree", "--name-only", revision, ".changeset/"]);
	return files.split("\n").some((file) => file.endsWith(".md") && file !== ".changeset/README.md");
}

async function openPullRequest(repository: string): Promise<string> {
	const listed = await output("gh", [
		"pr",
		"list",
		"--repo",
		repository,
		"--head",
		VERSION_BRANCH,
		"--base",
		"main",
		"--state",
		"open",
		"--json",
		"number",
		"--jq",
		".[0].number // empty",
	]);
	return listed.trim();
}

async function maintain(): Promise<void> {
	const repository = requiredEnv(process.env, "GITHUB_REPOSITORY");
	const token = requiredEnv(process.env, "GH_TOKEN");
	const firstParents = await output("git", ["rev-list", "--first-parent", "origin/main"]);
	const base = newestValidated(
		firstParents.split("\n").filter(Boolean),
		await validatedShas(repository),
	);
	if (base === undefined) {
		throw new Error("No recent main commit has a successful push CI/CD run");
	}
	const existing = await openPullRequest(repository);
	const current = await branchHead(VERSION_BRANCH);
	let versionedBase: string | undefined;
	if (existing !== "" && current !== undefined) {
		await run("git", ["fetch", "--no-tags", "origin", current]);
		const parent = await output("git", ["rev-parse", `${current}^`]);
		versionedBase = parent.trim();
	}
	const tipVersion = await packageVersion("origin/main");
	const reason = skipReason({
		base,
		baseVersion: await packageVersion(base),
		tipVersion,
		versionedBase,
		baseIsBehind:
			versionedBase !== undefined &&
			(await succeeds("git", ["merge-base", "--is-ancestor", base, versionedBase])),
		pendingChangesets: await hasChangesets(base),
	});
	if (reason !== undefined) {
		console.log(`The Version PR stays as it is: ${reason}.`);
		return;
	}

	// The scripts run from main's tip; only the versioned tree comes from the base.
	const worktree = await mkdtemp(path.join(process.env.RUNNER_TEMP ?? tmpdir(), "version-base-"));
	await run("git", ["worktree", "add", "--detach", worktree, base]);
	try {
		// Versioning needs no credential, so its dependencies get none.
		const withoutToken = { GH_TOKEN: undefined, GITHUB_TOKEN: undefined };
		await run("pnpm", ["install", "--frozen-lockfile", "--prefer-offline"], {
			cwd: worktree,
			env: withoutToken,
		});
		await run("vp", ["run", "release:version"], { cwd: worktree, env: withoutToken });
		const manifest = asRecord(
			parseJson(await readFile(path.join(worktree, "package.json"), "utf8")),
			"package.json",
		);
		const version = asString(manifest.version, "version");
		const pullRequest = versionPullRequest(
			await readFile(path.join(worktree, "CHANGELOG.md"), "utf8"),
			version,
			await prereleaseTag(base),
		);
		const changes = await stageChanges([], { cwd: worktree });
		if (changes.additions.length === 0 && changes.deletions.length === 0) {
			console.log(`The Version PR stays as it is: versioning ${base} changed no file.`);
			return;
		}
		const contents = new Map(
			await Promise.all(
				changes.additions.map(
					async (file) => [file, await readFile(path.join(worktree, file))] as const,
				),
			),
		);

		// A Version PR merge during versioning releases these changesets; recreating its branch
		// would release them again.
		await run("git", ["fetch", "--no-tags", "origin", "main"]);
		const latestVersion = await packageVersion("origin/main");
		if (latestVersion !== tipVersion || (await branchHead(VERSION_BRANCH)) !== current) {
			console.log("The Version PR stays as it is: main or the Version PR moved while versioning.");
			return;
		}
		await pointBranch(repository, STAGING_BRANCH, base);
		try {
			const target = {
				repository,
				branch: STAGING_BRANCH,
				expectedHeadOid: base,
				headline: pullRequest.title,
			};
			const commit = await createCommit(token, commitInput(target, contents, changes.deletions));
			await pointBranch(repository, VERSION_BRANCH, commit.oid);
			console.log(`Versioned ${base} as ${version} in ${commit.description}.`);
		} finally {
			// The next run force-resets a leftover staging branch, so a failed cleanup only logs.
			await gh([
				"--method",
				"DELETE",
				`repos/${repository}/git/refs/heads/${STAGING_BRANCH}`,
			]).catch((error: unknown) => {
				console.warn(`The staging branch remains: ${String(error)}`);
			});
		}

		const fields = ["--title", pullRequest.title, "--body", pullRequest.body];
		await (existing === ""
			? run("gh", [
					"pr",
					"create",
					"--repo",
					repository,
					"--head",
					VERSION_BRANCH,
					"--base",
					"main",
					...fields,
				])
			: run("gh", ["pr", "edit", existing, "--repo", repository, ...fields]));
	} finally {
		await run("git", ["worktree", "remove", "--force", worktree]);
		await rm(worktree, { recursive: true, force: true });
	}
}

if (import.meta.main) {
	await maintain();
}
