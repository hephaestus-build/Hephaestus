import { statfs } from "node:fs/promises";

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

/** The bytes free for an unprivileged user on the volume that holds `mount`. */
export async function availableBytes(mount: string): Promise<number> {
	const { bavail, bsize } = await statfs(mount);
	return bavail * bsize;
}

/**
 * The free bytes from which a job keeps the unused SDKs. Deleting them takes minutes on an x64
 * runner, and the hosted runners have far more free. The bar sits above each job's own minimum, so
 * a runner just above that minimum still gets the margin that the deletion gives.
 */
export const KEEP_SDKS_FREE_BYTES = 40 * 1024 ** 3;

/** Removes the unused SDKs only when the volume at `mount` has less than `KEEP_SDKS_FREE_BYTES` free. */
export async function freeDiskFor(mount: string): Promise<void> {
	if ((await availableBytes(mount)) < KEEP_SDKS_FREE_BYTES) {
		await removeUnusedSdks();
	}
}
