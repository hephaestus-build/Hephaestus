import { copyFile, mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";

import { isSet } from "./lib/env.ts";
import { run } from "./lib/process.ts";

const root = process.cwd();

/** `input` with `key` assigned `value`: replaced in place where it exists, appended otherwise. */
function setEnvValue(input: string, key: string, value: string): string {
	const assignment = `${key}=${value}`;
	if (new RegExp(`^${key}=`, "mu").test(input)) {
		return input.replace(new RegExp(`^${key}=.*$`, "mu"), assignment);
	}
	return `${input}${input.endsWith("\n") || input.length === 0 ? "" : "\n"}${assignment}\n`;
}

export function updateEnv(text: string): string {
	if (!/^GITLAB_PAT=.+$/mu.test(text) || !/^GITLAB_GROUP_PATH=.+$/mu.test(text)) {
		return text;
	}
	return setEnvValue(
		setEnvValue(
			setEnvValue(text, "GITLAB_WORKSPACE_INIT_DEFAULT", "true"),
			"GITLAB_ENABLED",
			"true",
		),
		"GITLAB_SERVER_URL",
		/^GITLAB_SERVER_URL=/mu.test(text)
			? (/^GITLAB_SERVER_URL=(?<url>.*)$/mu.exec(text)?.groups?.url ?? "https://gitlab.lrz.de")
			: "https://gitlab.lrz.de",
	);
}

async function copyFirst(
	rootPath: string,
	destination: string,
	candidates: string[],
): Promise<void> {
	for (const [index, candidate] of candidates.entries()) {
		try {
			await mkdir(path.dirname(path.join(root, destination)), { recursive: true });
			await copyFile(path.join(rootPath, candidate), path.join(root, destination));
		} catch (error) {
			if (error instanceof Error && "code" in error && error.code === "ENOENT") {
				continue;
			}
			throw error;
		}
		console.log(
			`  ${index ? "WARN: legacy layout — copied" : "copied"} ${destination}${index ? ` (from ${candidate})` : ""}`,
		);
		return;
	}
	console.log(`  skipped ${destination} (no candidate found in root: ${candidates.join(" ")})`);
}

async function copyLocalConfig(): Promise<void> {
	const jeanRoot = process.env.JEAN_ROOT_PATH;
	if (!isSet(jeanRoot)) {
		console.log("  JEAN_ROOT_PATH is not set — skipping config file copy.");
		return;
	}
	console.log("Copying local config files...");
	for (const [destination, candidates] of [
		[
			"server/application/src/main/resources/application-local.yml",
			["server/application/src/main/resources/application-local.yml"],
		],
		[
			"server/application/src/test/resources/application-live-local.yml",
			["server/application/src/test/resources/application-live-local.yml"],
		],
		["server/.env", ["server/.env"]],
		["docker/.env", ["docker/.env"]],
		[".claude/settings.local.json", [".claude/settings.local.json"]],
	] satisfies [string, string[]][]) {
		await copyFirst(jeanRoot, destination, candidates);
	}
}

async function main(): Promise<void> {
	console.log("Setting up Jean worktree...");
	await copyLocalConfig();
	const envPath = path.join(root, "server/.env");
	let before: string | undefined;
	try {
		before = await readFile(envPath, "utf8");
	} catch (error) {
		if (!(error instanceof Error && "code" in error && error.code === "ENOENT")) {
			throw error;
		}
	}
	if (before !== undefined) {
		const after = updateEnv(before);
		if (after !== before) {
			await writeFile(envPath, after, { mode: 0o600 });
			console.log("  enabled GitLab default workspace bootstrap from local server/.env");
		}
	}
	console.log("Installing dependencies...");
	await run("vp", ["install", "--frozen-lockfile"], { cwd: root });
	console.log("✅ Jean worktree setup complete.");
}

if (import.meta.main) {
	await main();
}
