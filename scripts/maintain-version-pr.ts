/**
 * Maintains the Version PR from the checked-out `VERSION_BASE`, the main commit that
 * `version-pr.yml` selected. docs/contributor/ci-cd.mdx § Merge policy owns the contract.
 */
import { existsSync } from "node:fs";
import { readFile } from "node:fs/promises";

import { commitInput, createCommit, stageChanges } from "./commit-via-api.ts";
import { requiredEnv } from "./lib/env.ts";
import { asRecord, asString, parseJson } from "./lib/json.ts";
import { output, run } from "./lib/process.ts";

export const VERSION_BRANCH = "changeset-release/main";
// The commit is made on this branch first. Resetting the Version PR's branch to a main commit
// before the commit exists would leave its head inside main, and GitHub marks such a PR merged.
const STAGING_BRANCH = "version-pr-staging/main";
const TITLE = "chore(release): version packages";

export interface MaintenanceState {
	readonly base: string;
	readonly baseVersion: string;
	readonly tipVersion: string;
	readonly versionedBase: string | undefined;
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
	return {
		title: prerelease === undefined ? TITLE : `${TITLE} (${prerelease})`,
		body: `Merging this pull request releases \`${version}\`. \`version-pr.yml\` maintains it from the newest \`main\` commit whose CI/CD succeeded.\n\n# Releases\n## hephaestus@${version}\n\n${notes}\n`,
	};
}

const gh = async (args: string[], input?: string) => output("gh", ["api", ...args], { input });

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

async function prereleaseTag(): Promise<string | undefined> {
	if (!existsSync(".changeset/pre.json")) {
		return undefined;
	}
	const state = asRecord(parseJson(await readFile(".changeset/pre.json", "utf8")), "pre state");
	return state.mode === "pre" ? asString(state.tag, "pre tag") : undefined;
}

async function packageVersion(revision: string): Promise<string> {
	const manifest = asRecord(
		parseJson(await output("git", ["show", `${revision}:package.json`])),
		"package.json",
	);
	return asString(manifest.version, "version");
}

async function hasChangesets(): Promise<boolean> {
	const files = await output("git", ["ls-files", "--", ".changeset/*.md"]);
	return files.split("\n").some((file) => file !== "" && file !== ".changeset/README.md");
}

async function maintain(): Promise<void> {
	const repository = requiredEnv(process.env, "GITHUB_REPOSITORY");
	const token = requiredEnv(process.env, "GH_TOKEN");
	const base = requiredEnv(process.env, "VERSION_BASE");
	const head = await output("git", ["rev-parse", "HEAD"]);
	if (head.trim() !== base) {
		throw new Error(`The checkout is not the selected base ${base}`);
	}
	const current = await branchHead(VERSION_BRANCH);
	let versionedBase: string | undefined;
	if (current !== undefined) {
		await run("git", ["fetch", "--no-tags", "origin", current]);
		const parent = await output("git", ["rev-parse", `${current}^`]);
		versionedBase = parent.trim();
	}
	const reason = skipReason({
		base,
		baseVersion: await packageVersion(base),
		tipVersion: await packageVersion("origin/main"),
		versionedBase,
		pendingChangesets: await hasChangesets(),
	});
	if (reason !== undefined) {
		console.log(`The Version PR stays as it is: ${reason}.`);
		return;
	}
	await run("vp", ["run", "release:version"]);
	const tag = await prereleaseTag();
	const manifest = asRecord(parseJson(await readFile("package.json", "utf8")), "package.json");
	const version = asString(manifest.version, "version");
	const pullRequest = versionPullRequest(await readFile("CHANGELOG.md", "utf8"), version, tag);

	const changes = await stageChanges();
	if (changes.additions.length === 0 && changes.deletions.length === 0) {
		console.log(`Versioning ${base} changed no file; the Version PR stays as it is.`);
		return;
	}
	const contents = new Map(
		await Promise.all(changes.additions.map(async (path) => [path, await readFile(path)] as const)),
	);
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
		await gh(["--method", "DELETE", `repos/${repository}/git/refs/heads/${STAGING_BRANCH}`]);
	}

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
	const existing = listed.trim();
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
}

if (import.meta.main) {
	await maintain();
}
