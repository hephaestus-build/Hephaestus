import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import path from "node:path";

import { parse } from "yaml";

import { isSet } from "./lib/env.ts";
import { environmentWithoutGitRepository } from "./lib/git-environment.ts";
import { isRecord } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES, repositoryCli } from "./lib/process.ts";

export type Scope = "agents" | "docs" | "extension" | "full" | "server" | "webapp";
/** The arguments of one repository CLI call. */
export type Command = readonly [string, ...string[]];

export function parseBase(args: string[]): string {
	if (args.length === 0) {
		return "origin/main";
	}
	const [flag, revision] = args;
	if (args.length === 2 && flag === "--base" && isSet(revision)) {
		return revision;
	}
	throw new Error("Usage: vp run check:affected [--base <revision>]");
}

const fullGateInputs = [
	/^\.github\//u,
	/^\.agents\//u,
	/^\.claude\//u,
	/^\.changeset\//u,
	/^scripts\//u,
	/^package\.json$/u,
	/^pnpm-lock\.yaml$/u,
	/^pnpm-workspace\.yaml$/u,
	/^patches\//u,
	/^tsconfig(?:\.agents)?\.json$/u,
	/^\.ox(?:fmt|lint)rc\.json$/u,
	/^server\/openapi\.yaml$/u,
	/^webapp\/src\/api\//u,
	/^extension\/src\/api\//u,
	/^webapp\/src\/routeTree\.gen\.ts$/u,
	/^webapp\/tools\/oxlint\//u,
	/^docs\/contributor\/erd\/schema\.mmd$/u,
	/(?:^|\/)AGENTS\.md$/u,
	/(?:^|\/)CLAUDE\.md$/u,
];

// The palette both trees import, and the docs site's copies are pinned to (`gate:docs-tokens`).
const SHARED_THEME_TOKENS = "webapp/src/styles/theme-tokens.css";

// Webapp files the extension imports by path (`@/…`), the files those import, and the stylesheet
// the formatter sorts the extension's Tailwind classes by; `check-affected.test.ts` derives them from
// the extension's imports and holds this list and the `extension` filters in `cicd.yml` to them. The
// webapp lint plugin, which both trees load, is a full-gate input above.
const webappInputsOfTheExtension = [
	/^webapp\/brand\/hephaestus-mark\.svg$/u,
	/^webapp\/src\/components\/brand\/HephaestusLogo\.tsx$/u,
	/^webapp\/src\/components\/ui\/(?:skeleton|spinner)\.tsx$/u,
	/^webapp\/src\/components\/icons\/brand\.tsx$/u,
	/^webapp\/src\/components\/practice-vocabulary\//u,
	/^webapp\/src\/components\/common\/(?:status-def\.ts|FacetMultiSelect\.tsx)$/u,
	/^webapp\/src\/lib\/(?:artifact-kind-slugs|artifact-kinds|item-kinds|sign-in-providers|text)\.ts$/u,
	/^webapp\/src\/styles\.css$/u,
];

// Server files the webapp's tests and stories read, so they cannot drift from them unseen. Keep in
// step with the `webapp` filter in `cicd.yml`.
const serverInputsOfTheWebapp = [
	/^server\/application\/src\/main\/resources\/practices\/default-catalog\.json$/u,
	/^server\/application\/src\/main\/resources\/practices\/guidance\//u,
	/^server\/application\/src\/main\/resources\/contracts\/source-use\//u,
	/^server\/application\/src\/test\/resources\/agent\/internal-feedback-terms\.json$/u,
	/^server\/application\/src\/main\/java\/de\/tum\/cit\/aet\/hephaestus\/practices\/PracticeGuidanceRules\.java$/u,
];

// Server resources the tooling tests judge (`practice-catalog-schema.test.ts`, `practice-guidance.test.ts`).
// Keep in step with the `tooling` filter in `cicd.yml`.
const serverInputsOfTheTooling = [
	/^server\/application\/src\/main\/resources\/practices\/default-catalog(?:\.schema)?\.json$/u,
	/^server\/application\/src\/main\/resources\/practices\/guidance\//u,
];

export function scopesFor(paths: string[]): Scope[] {
	if (paths.some((file) => fullGateInputs.some((pattern) => pattern.test(file)))) {
		return ["full"];
	}
	const scopes = new Set<Scope>();
	for (const file of paths) {
		if (file.startsWith("docs/images/readme/")) {
			scopes.add("docs");
			scopes.add("webapp");
		} else if (file === SHARED_THEME_TOKENS) {
			scopes.add("docs");
			scopes.add("extension");
			scopes.add("webapp");
		} else if (webappInputsOfTheExtension.some((pattern) => pattern.test(file))) {
			scopes.add("extension");
			scopes.add("webapp");
		} else if (file.startsWith("webapp/")) {
			scopes.add("webapp");
		} else if (file.startsWith("extension/")) {
			scopes.add("extension");
		} else if (file.startsWith("server/")) {
			if (serverInputsOfTheTooling.some((pattern) => pattern.test(file))) {
				scopes.add("agents");
			}
			if (serverInputsOfTheWebapp.some((pattern) => pattern.test(file))) {
				scopes.add("server");
				scopes.add("webapp");
			} else if (/\/resources\/(?:agent|practices\/scripts)\//u.test(file)) {
				scopes.add("agents");
			} else {
				scopes.add("server");
			}
		} else if (file.startsWith("docker/agents/")) {
			scopes.add("agents");
		} else if (file.startsWith("deploy/chart/")) {
			// The chart suites run in test:tooling, which affected:agents runs.
			scopes.add("agents");
		} else if (file.startsWith("docs/")) {
			scopes.add("docs");
		} else {
			return ["full"];
		}
	}
	return [...scopes].toSorted();
}

function git(cwd: string, ...args: string[]): string[] {
	const result = spawnSync("git", args, {
		cwd,
		encoding: "utf8",
		maxBuffer: CAPTURE_LIMIT_BYTES,
		env: environmentWithoutGitRepository(),
	});
	if (result.status !== 0) {
		throw new Error(`git ${args.join(" ")} failed: ${result.stderr.trim() || "unknown error"}`);
	}
	return result.stdout.split("\n").filter(Boolean);
}

export function changedPaths(requestedBase: string, cwd = process.cwd()): string[] {
	const base = git(cwd, "merge-base", "HEAD", requestedBase)[0];
	if (base === undefined) {
		throw new Error(`No merge base with ${requestedBase}`);
	}
	return [
		...new Set([
			...git(cwd, "diff", "--no-renames", "--name-only", `${base}...HEAD`),
			...git(cwd, "diff", "--no-renames", "--name-only"),
			...git(cwd, "diff", "--cached", "--no-renames", "--name-only"),
			...git(cwd, "ls-files", "--others", "--exclude-standard"),
		]),
	].toSorted();
}

export function commandsFor(scopes: Scope[]): Command[] {
	if (scopes.includes("full")) {
		return [["run", "quality"]];
	}
	const commands: Record<Exclude<Scope, "full">, Command> = {
		agents: ["run", "affected:agents"],
		docs: ["run", "affected:docs"],
		extension: ["run", "affected:extension"],
		server: ["run", "affected:server"],
		webapp: ["run", "affected:webapp"],
	};
	return scopes.map((scope) => {
		if (scope === "full") {
			throw new Error("Full scope must override scoped commands");
		}
		return commands[scope];
	});
}

function run(command: Command): void {
	const result = spawnSync(process.execPath, [repositoryCli(), ...command], { stdio: "inherit" });
	if (result.status !== 0) {
		process.exit(result.status ?? 1);
	}
}

/**
 * The globs of the `kubernetes` path filter in `cicd.yml`: a change to them runs the k3d cluster
 * jobs in CI.
 */
export function clusterGlobs(workflow: string): string[] {
	const parsed: unknown = parse(workflow);
	const jobs = isRecord(parsed) && isRecord(parsed.jobs) ? Object.values(parsed.jobs) : [];
	for (const job of jobs) {
		const steps = isRecord(job) && Array.isArray(job.steps) ? job.steps : [];
		for (const step of steps) {
			if (isRecord(step) && isRecord(step.with) && typeof step.with.filters === "string") {
				const filters: unknown = parse(step.with.filters);
				if (isRecord(filters) && Array.isArray(filters.kubernetes)) {
					return filters.kubernetes.map(String);
				}
			}
		}
	}
	throw new Error("cicd.yml has no kubernetes path filter");
}

/**
 * Whether a changed path is one that the CI cluster jobs exercise. Git paths use `/` on every
 * system, so the POSIX matcher reads them. `matchesGlob` skips
 * dotfiles, which CI's filter matches, so a change to only a dotfile gets no reminder.
 */
export function touchesCluster(paths: string[], globs: string[]): boolean {
	return paths.some((file) => globs.some((glob) => path.posix.matchesGlob(file, glob)));
}

/** Whether to remind of the cluster. A hint: a workflow that it cannot read skips it and fails no check. */
function hintsCluster(paths: string[]): boolean {
	try {
		const workflow = readFileSync(
			path.join(import.meta.dirname, "..", ".github", "workflows", "cicd.yml"),
			"utf8",
		);
		return touchesCluster(paths, clusterGlobs(workflow));
	} catch {
		return false;
	}
}

function main(): void {
	const requestedBase = parseBase(process.argv.slice(2));
	const paths = changedPaths(requestedBase);
	const scopes = scopesFor(paths);
	if (scopes.length === 0) {
		console.log("No changes detected; no checks ran.");
		return;
	}
	if (scopes.includes("full")) {
		console.log(
			"Shared, generated, or unknown input changed; expanding to the complete local quality gate.",
		);
	} else {
		console.log(
			`Running affected checks for: ${scopes.join(", ")}. This is not the complete local gate.`,
		);
	}
	const clusterHint = hintsCluster(paths);
	for (const command of commandsFor(scopes)) {
		run(command);
	}
	if (clusterHint) {
		console.log(
			"This change runs the CI cluster jobs. Run `vp run dev:kubernetes` to check it on a local cluster.",
		);
	}
	if (scopes.includes("full")) {
		console.log("Complete local quality gate passed.");
	} else {
		console.log(
			"Affected checks passed. The complete local gate has not run; use `vp run check` before pushing.",
		);
	}
}

if (import.meta.main) {
	main();
}
