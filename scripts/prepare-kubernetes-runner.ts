import { appendFile, statfs } from "node:fs/promises";

import { output, run } from "./lib/process.ts";

// Node containers unpack separate copies of the application and sandbox images on Docker's filesystem.
const MINIMUM_FREE_BYTES = 25 * 1024 ** 3;
const UNUSED_SDKS = [
	"/usr/local/lib/android",
	"/usr/share/dotnet",
	"/usr/local/.ghcup",
	"/opt/ghc",
	"/opt/hostedtoolcache/CodeQL",
];

export function checkFreeSpace(available: number): string {
	if (!Number.isSafeInteger(available) || available < 0) {
		throw new Error("Could not read available filesystem bytes");
	}
	const message = `Docker filesystem: ${available} bytes (${(available / 1024 ** 3).toFixed(1)} GiB) free; these cluster tests require at least ${MINIMUM_FREE_BYTES / 1024 ** 3} GiB before cluster creation.`;
	if (available < MINIMUM_FREE_BYTES) {
		throw new Error(message);
	}
	return message;
}

async function main(): Promise<void> {
	if (
		process.platform !== "linux" ||
		process.env.GITHUB_ACTIONS !== "true" ||
		process.env.RUNNER_ENVIRONMENT !== "github-hosted"
	) {
		throw new Error("Disk cleanup requires a GitHub-hosted Linux runner");
	}
	const dockerRoot = await output("docker", ["info", "--format", "{{.DockerRootDir}}"]);
	const root = dockerRoot.trim();
	if (!root.startsWith("/") || root.includes("\n")) {
		throw new Error("Docker did not return an absolute data directory");
	}
	await run("df", ["-h", "--", root]);
	await run("sudo", ["rm", "-rf", "--", ...UNUSED_SDKS]);
	await run("df", ["-h", "--", root]);
	const { bavail, bsize } = await statfs(root);
	const message = checkFreeSpace(bavail * bsize);
	console.log(message);
	if (process.env.GITHUB_STEP_SUMMARY !== undefined) {
		await appendFile(process.env.GITHUB_STEP_SUMMARY, `${message}\n`);
	}
}

if (import.meta.main) {
	await main().catch((error: unknown) => {
		console.error(`::error::${error instanceof Error ? error.message : String(error)}`);
		process.exitCode = 1;
	});
}
