import { spawn } from "node:child_process";
import { createWriteStream } from "node:fs";
import { mkdtemp, rm } from "node:fs/promises";
import path from "node:path";
import { Transform } from "node:stream";
import { pipeline } from "node:stream/promises";
import { setTimeout as sleep } from "node:timers/promises";

import { discoverCapabilities, present } from "./gateway-capabilities.ts";

const DOWNLOAD_ATTEMPTS = 3;
const DOWNLOAD_BACKOFF_MS = 500;

/** Download a complete archive before extraction, so a broken transfer leaves no partial workspace. */
export async function downloadWorkspace(
	endpoint: string,
	token: string,
	archive: string,
): Promise<void> {
	const headers = { Authorization: `Bearer ${token}` };
	const { workspaceByteBudget: budget } = await discoverCapabilities(endpoint, headers);
	for (let attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt += 1) {
		let retryable = true;
		try {
			const response = await fetch(`${endpoint}/workspace`, { headers });
			if (!response.ok || !response.body) {
				retryable = response.status === 425 || response.status >= 500;
				await response.body?.cancel();
				throw new Error(`Workspace download refused: ${response.status}`);
			}
			let bytes = 0;
			const bound = new Transform({
				// oxlint-disable-next-line promise/prefer-await-to-callbacks -- Node's Transform API takes a callback.
				transform(chunk: Buffer, _encoding, callback) {
					bytes += chunk.length;
					// oxlint-disable-next-line promise/prefer-await-to-callbacks -- The same callback, answered.
					callback(
						bytes > budget ? new Error("Workspace exceeded its advertised byte budget") : null,
						chunk,
					);
				},
			});
			try {
				await pipeline(response.body, bound, createWriteStream(archive));
			} catch (error) {
				if (bytes > budget) {
					retryable = false;
					throw new Error("Workspace exceeded its advertised byte budget", { cause: error });
				}
				throw error;
			}
			if (bytes !== budget) {
				throw new Error("Incomplete workspace download");
			}
			return;
		} catch (error) {
			if (!retryable || attempt === DOWNLOAD_ATTEMPTS) {
				throw error;
			}
			console.error(`workspace download attempt ${attempt} failed: ${String(error)}`);
			await sleep(DOWNLOAD_BACKOFF_MS * 2 ** (attempt - 1));
		}
	}
}

async function extract(archive: string): Promise<void> {
	const child = spawn(
		"tar",
		[
			"--extract",
			"--file",
			archive,
			"--directory",
			"/workspace",
			"--no-same-owner",
			"--no-same-permissions",
			"--delay-directory-restore",
		],
		{ stdio: "inherit" },
	);
	const extracted = Promise.withResolvers<undefined>();
	child.once("error", extracted.reject);
	child.once("exit", (code) => {
		if (code === 0) {
			extracted.resolve(undefined);
		} else {
			extracted.reject(new Error(`Workspace extraction failed: ${code}`));
		}
	});
	await extracted.promise;
}

async function main() {
	const endpoint = process.env.SANDBOX_RUNTIME_URL;
	const token = process.env.LLM_PROXY_TOKEN;
	if (!present(endpoint) || !present(token)) {
		throw new Error("Gateway credential and runtime endpoint are required");
	}
	const directory = await mkdtemp("/workspace/.gateway-download-");
	try {
		const archive = path.join(directory, "workspace.tar");
		await downloadWorkspace(endpoint, token, archive);
		await extract(archive);
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
}

if (import.meta.main) {
	try {
		await main();
	} catch (error) {
		console.error(error);
		process.exitCode = 1;
	}
}
