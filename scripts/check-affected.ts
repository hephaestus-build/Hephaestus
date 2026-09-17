import { spawnSync } from "node:child_process";

import { isSet } from "./lib/env.ts";
import { environmentWithoutGitRepository } from "./lib/git-environment.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

export type Scope = "agents" | "docs" | "full" | "server" | "webapp";
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
	/^webapp\/src\/routeTree\.gen\.ts$/u,
	/^webapp\/tools\/oxlint\//u,
	/^docs\/contributor\/erd\/schema\.mmd$/u,
	/(?:^|\/)AGENTS\.md$/u,
	/(?:^|\/)CLAUDE\.md$/u,
];

export function scopesFor(paths: string[]): Scope[] {
	if (paths.some((path) => fullGateInputs.some((pattern) => pattern.test(path)))) {
		return ["full"];
	}
	const scopes = new Set<Scope>();
	for (const path of paths) {
		if (path.startsWith("docs/images/readme/")) {
			scopes.add("docs");
			scopes.add("webapp");
		} else if (path.startsWith("webapp/")) {
			scopes.add("webapp");
		} else if (path.startsWith("server/")) {
			if (/\/resources\/(?:agent|practices\/precompute)\//u.test(path)) {
				scopes.add("agents");
			} else {
				scopes.add("server");
			}
		} else if (path.startsWith("docker/agents/")) {
			scopes.add("agents");
		} else if (path.startsWith("docs/")) {
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
		return [["vp", "run", "quality"]];
	}
	const commands: Record<Exclude<Scope, "full">, Command> = {
		agents: ["vp", "run", "affected:agents"],
		docs: ["vp", "run", "affected:docs"],
		server: ["vp", "run", "affected:server"],
		webapp: ["vp", "run", "affected:webapp"],
	};
	return scopes.map((scope) => {
		if (scope === "full") {
			throw new Error("Full scope must override scoped commands");
		}
		return commands[scope];
	});
}

function run(command: Command): void {
	const [executable, ...arguments_] = command;
	const result = spawnSync(executable, arguments_, { stdio: "inherit" });
	if (result.status !== 0) {
		process.exit(result.status ?? 1);
	}
}

function main(): void {
	const requestedBase = parseBase(process.argv.slice(2));
	const scopes = scopesFor(changedPaths(requestedBase));
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
	for (const command of commandsFor(scopes)) {
		run(command);
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
