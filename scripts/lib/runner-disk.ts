import { run } from "./process.ts";

/** Preinstalled SDKs that no Hephaestus job uses; removing them frees most of a hosted runner's disk. */
export const UNUSED_SDKS = [
	"/usr/local/lib/android",
	"/usr/share/dotnet",
	"/usr/local/.ghcup",
	"/opt/ghc",
	"/opt/hostedtoolcache/CodeQL",
];

/** Disk cleanup deletes system directories, so it refuses anything but a GitHub-hosted Linux runner. */
export function requireHostedRunner(): void {
	if (
		process.platform !== "linux" ||
		process.env.GITHUB_ACTIONS !== "true" ||
		process.env.RUNNER_ENVIRONMENT !== "github-hosted"
	) {
		throw new Error("Disk cleanup requires a GitHub-hosted Linux runner");
	}
}

export async function removeUnusedSdks(): Promise<void> {
	await run("sudo", ["rm", "-rf", "--", ...UNUSED_SDKS]);
}
