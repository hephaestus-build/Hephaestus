import { writeFile } from "node:fs/promises";
import path from "node:path";

import { isSet } from "./env.ts";
import { run } from "./process.ts";

const DOWNLOAD_TIMEOUT_MS = 5 * 60_000;

export interface ReleaseAssetSource {
	environment: NodeJS.ProcessEnv;
	download: (repository: string, release: string, names: string[], into: string) => Promise<void>;
}

/** Draft assets require authenticated access; installed hosts download published assets directly. */
export async function downloadReleaseAssets(
	repository: string,
	release: string,
	names: string[],
	into: string,
): Promise<void> {
	if (process.env.GITHUB_ACTIONS === "true") {
		if (!isSet(process.env.GH_TOKEN)) {
			throw new Error("GH_TOKEN is required to verify a draft release");
		}
		await run(
			"gh",
			[
				"release",
				"download",
				release,
				"--repo",
				repository,
				"--dir",
				into,
				...names.flatMap((name) => ["--pattern", name]),
			],
			{ signal: AbortSignal.timeout(DOWNLOAD_TIMEOUT_MS), stdin: "ignore" },
		);
		return;
	}
	await Promise.all(
		names.map(async (name) => {
			const url = `https://github.com/${repository}/releases/download/${release}/${name}`;
			const response = await fetch(url, { signal: AbortSignal.timeout(DOWNLOAD_TIMEOUT_MS) });
			if (!response.ok) {
				throw new Error(`GET ${url} returned ${response.status}`);
			}
			await writeFile(path.join(into, name), Buffer.from(await response.arrayBuffer()));
		}),
	);
}
