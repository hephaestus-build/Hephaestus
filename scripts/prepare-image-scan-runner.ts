/**
 * Frees runner disk before the image vulnerability scans and gives Trivy a temporary directory on
 * the volume with the most free space. Trivy copies each image layer to a temporary file, and the
 * builds before it have filled the root volume, so the default temporary directory can run out.
 */
import { appendFile, statfs } from "node:fs/promises";
import { userInfo } from "node:os";
import path from "node:path";

import { requiredEnv } from "./lib/env.ts";
import { run } from "./lib/process.ts";
import { KEEP_SDKS_FREE_BYTES, removeUnusedSdks, requireHostedRunner } from "./lib/runner-disk.ts";

export interface Volume {
	readonly mount: string;
	readonly available: number;
}

/** The volume with the most free bytes; the first one wins a tie. */
export function roomiest(volumes: readonly Volume[]): Volume {
	let best: Volume | undefined;
	for (const volume of volumes) {
		if (best === undefined || volume.available > best.available) {
			best = volume;
		}
	}
	if (best === undefined) {
		throw new Error("No volume is available for the scan's temporary files");
	}
	return best;
}

async function available(mount: string): Promise<Volume | undefined> {
	const stats = await statfs(mount).catch(() => undefined);
	return stats === undefined ? undefined : { mount, available: stats.bavail * stats.bsize };
}

/**
 * The roomiest volume for the scans. Only if it is short are the SDKs removed, and the volumes
 * measured again: the deletion frees the root volume, which can then be the roomiest.
 */
export async function scanVolume(
	measure: () => Promise<readonly Volume[]>,
	removeSdks: () => Promise<void>,
): Promise<Volume> {
	const chosen = roomiest(await measure());
	if (chosen.available >= KEEP_SDKS_FREE_BYTES) {
		return chosen;
	}
	await removeSdks();
	return roomiest(await measure());
}

async function main(): Promise<void> {
	requireHostedRunner();
	const runnerTemp = requiredEnv(process.env, "RUNNER_TEMP");
	// Hosted x64 runners mount a second disk at /mnt; arm64 runners may not.
	const candidates = [runnerTemp, "/mnt"];
	await run("df", ["-h"]);
	const chosen = await scanVolume(async () => {
		const measured = await Promise.all(candidates.map(available));
		return measured.filter((volume): volume is Volume => volume !== undefined);
	}, removeUnusedSdks);
	const directory = path.join(chosen.mount, "image-scan-tmp");
	const { uid, gid } = userInfo();
	await run("sudo", ["install", "-d", "-o", String(uid), "-g", String(gid), directory]);
	await run("df", ["-h"]);
	console.log(
		`Image scans use ${directory}: ${(chosen.available / 1024 ** 3).toFixed(1)} GiB free.`,
	);
	await appendFile(requiredEnv(process.env, "GITHUB_OUTPUT"), `directory=${directory}\n`);
}

if (import.meta.main) {
	await main().catch((error: unknown) => {
		console.error(`::error::${error instanceof Error ? error.message : String(error)}`);
		process.exitCode = 1;
	});
}
