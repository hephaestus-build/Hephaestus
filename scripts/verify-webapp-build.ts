/**
 * Builds the webapp, checks the committed route tree, and holds the bundle budget against the build
 * of the commit this change merges onto: `BUNDLE_BASE`, which the merge queue sets to the entry ahead,
 * or the merge base with `origin/main`. Both builds use one install, so neither a stale install nor
 * growth that landed earlier counts against the change.
 */
import { spawnSync } from "node:child_process";
import { copyFileSync, mkdtempSync, readFileSync, rmSync, symlinkSync } from "node:fs";
import { readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";

import { isSet } from "./lib/env.ts";
import { staleInstall } from "./lib/install.ts";
import { isRecord } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES, repositoryCli } from "./lib/process.ts";

const ROOT = path.resolve(import.meta.dirname, "..");
const METADATA = "webapp/dist/.vite/bundle-metadata.json";
/** What decides the bundle besides the webapp tree. */
const BUNDLE_INPUTS = [
	"webapp",
	"package.json",
	"pnpm-lock.yaml",
	"pnpm-workspace.yaml",
	"patches",
];

function git(...args: string[]): string {
	const result = spawnSync("git", args, {
		cwd: ROOT,
		encoding: "utf8",
		maxBuffer: CAPTURE_LIMIT_BYTES,
	});
	if (result.status !== 0) {
		throw new Error(`git ${args.join(" ")} failed: ${result.stderr.trim()}`);
	}
	return result.stdout.trim();
}

function differs(base: string, ...paths: string[]): boolean {
	return (
		spawnSync("git", ["diff", "--quiet", base, "--", ...paths], { cwd: ROOT }).status !== 0 ||
		git("ls-files", "--others", "--exclude-standard", "--", ...paths) !== ""
	);
}

function run(
	program: string,
	args: string[],
	cwd: string,
	env: NodeJS.ProcessEnv = process.env,
): void {
	const status = spawnSync(program, args, { cwd, env, stdio: "inherit" }).status ?? 1;
	if (status !== 0) {
		throw new Error(`${path.basename(program)} ${args.join(" ")} exited with ${status}`);
	}
}

/** The `vp` entry of the install in `root`, as `repositoryCli` resolves it for this checkout. */
function viteCli(root: string): string {
	const directory = path.join(root, "node_modules", "vite-plus");
	const manifest: unknown = JSON.parse(readFileSync(path.join(directory, "package.json"), "utf8"));
	const target =
		isRecord(manifest) && isRecord(manifest.exports) && isRecord(manifest.exports["./bin"])
			? manifest.exports["./bin"].import
			: undefined;
	if (typeof target !== "string") {
		throw new Error(`${directory} has no \`./bin\` export`);
	}
	return path.join(directory, target);
}

function refuseStaleInstall(why: string): void {
	const missing = staleInstall(ROOT);
	if (missing > 0) {
		throw new Error(
			`${missing} locked package versions are not installed in this checkout, so ${why}. Run \`pnpm install --frozen-lockfile\` and retry.`,
		);
	}
}

function mergeBase(): string {
	try {
		return git("merge-base", "HEAD", "origin/main");
	} catch {
		throw new Error(
			"This checkout has no merge base with origin/main to compare the bundle with. Run `git fetch origin main` and retry.",
		);
	}
}

const updating = process.env.BUNDLE_BUDGET === "update";
if (updating) {
	refuseStaleInstall("the sizes it would record differ from CI's");
}

/** Who accepts the growth: `BUNDLE_ACCEPTED_BY`, else this branch's pull request, else the branch. */
function acceptedBy(): string {
	const given = process.env.BUNDLE_ACCEPTED_BY;
	if (isSet(given)) {
		return given;
	}
	const pullRequest = spawnSync("gh", ["pr", "view", "--json", "url", "--jq", ".url"], {
		cwd: ROOT,
		encoding: "utf8",
		timeout: 20_000,
	});
	const url = pullRequest.status === 0 ? pullRequest.stdout.trim() : "";
	return url === "" ? `branch ${git("branch", "--show-current")}` : url;
}

const routeTree = "webapp/src/routeTree.gen.ts";
const before = await readFile(routeTree);
let status = 1;
let changed = false;
try {
	status =
		spawnSync(process.execPath, [repositoryCli(), "run", "build:webapp"], { stdio: "inherit" })
			.status ?? 1;
	if (status === 0) {
		changed = !before.equals(await readFile(routeTree));
	}
} finally {
	await writeFile(routeTree, before);
}
if (status !== 0) {
	process.exit(status);
}
if (changed) {
	throw new Error("webapp/src/routeTree.gen.ts is stale; run `vp run build:webapp`");
}

const requested = process.env.BUNDLE_BASE;
const base = isSet(requested) ? requested : mergeBase();
const baseName = git("log", "-1", "--format=%h (%s)", base);
if (!differs(base, ...BUNDLE_INPUTS)) {
	console.log(`No bundle input differs from ${baseName}, so the bundle cannot have grown.`);
	process.exit(0);
}

const started = Date.now();
const directory = mkdtempSync(path.join(tmpdir(), "webapp-base-build-"));
const links: string[] = [];
try {
	const archive = path.join(directory, "base.tar");
	const copy = path.join(directory, "base");
	git("archive", "--prefix=base/", `--output=${archive}`, base);
	run("tar", ["-xf", archive, "-C", directory], ROOT);
	const head = path.join(directory, "head-metadata.json");
	copyFileSync(path.join(ROOT, METADATA), head);
	if (differs(base, "pnpm-lock.yaml")) {
		refuseStaleInstall("its build cannot be compared with the base's own install");
		run(
			process.execPath,
			[repositoryCli(), "install", "--frozen-lockfile", "--ignore-scripts", "--prefer-offline"],
			copy,
		);
	} else {
		// The same install: a junction, because Windows creates one without extra rights.
		for (const tree of [".", "webapp"]) {
			const link = path.join(copy, tree, "node_modules");
			symlinkSync(path.join(ROOT, tree, "node_modules"), link, "junction");
			links.push(link);
		}
	}
	const cli = viteCli(copy);
	// A base from before this comparison checks its own committed baseline in its build; in update
	// mode it writes that throwaway copy's file instead of failing on a stale number there.
	run(process.execPath, [cli, "-C", "webapp", "build"], copy, {
		...process.env,
		BUNDLE_BUDGET: "update",
	});
	console.log(`The base build took ${Math.round((Date.now() - started) / 1000)} s.`);
	const compared = spawnSync(
		process.execPath,
		[
			"webapp/tools/bundle-budget/compare.ts",
			head,
			path.join(copy, METADATA),
			baseName,
			path.join(copy, "webapp", "tools", "bundle-budget", "accepted-growth.json"),
		],
		{
			cwd: ROOT,
			stdio: "inherit",
			env: updating ? { ...process.env, BUNDLE_ACCEPTED_BY: acceptedBy() } : process.env,
		},
	);
	process.exitCode = compared.status ?? 1;
} finally {
	// Unlink the junctions first: a recursive removal must never reach the install they point at.
	for (const link of links) {
		rmSync(link, { force: true });
	}
	rmSync(directory, { recursive: true, force: true });
}
