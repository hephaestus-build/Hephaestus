import { type ChildProcess, spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { createReadStream, openAsBlob } from "node:fs";
import { mkdtemp, rm } from "node:fs/promises";
import { constants, tmpdir } from "node:os";
import path from "node:path";
import { setTimeout as sleep } from "node:timers/promises";

import { present } from "./gateway-capabilities.ts";

// The worker follows SIGTERM with SIGKILL after its stop timeout; the child gets this much of it and
// the upload of whatever out/ holds by then gets the rest.
const CHILD_STOP_GRACE_MS = 3000;
const UPLOAD_ATTEMPTS = 3;
const UPLOAD_BACKOFF_MS = 500;

function exitCodeOf(code: number | null, signal: NodeJS.Signals | null): number {
	if (code !== null) {
		return code;
	}
	return 128 + (signal === null ? 0 : constants.signals[signal]);
}

/** The child's exit code, or the error that kept it from running. */
async function exited(child: ChildProcess) {
	const done = Promise.withResolvers<number>();
	child.once("error", done.reject);
	child.once("exit", (code, signal) => done.resolve(exitCodeOf(code, signal)));
	return done.promise;
}

async function run(command: string, args: string[]) {
	return exited(spawn(command, args, { stdio: "inherit" }));
}

function retryable(status: number): boolean {
	return status === 425 || status >= 500;
}

export async function upload(url: URL, token: string, archive: string): Promise<void> {
	const hash = createHash("sha256");
	for await (const chunk of createReadStream(archive)) {
		const value: unknown = chunk;
		if (!Buffer.isBuffer(value)) {
			throw new Error("Archive stream returned non-binary data");
		}
		hash.update(value);
	}
	const digest = hash.digest();
	const contentDigest = `sha-256=:${digest.toString("base64")}:`;
	const etag = `"${digest.toString("hex")}"`;
	for (let attempt = 1; ; attempt += 1) {
		let response: Response;
		try {
			response = await fetch(url, {
				method: "POST",
				headers: {
					Authorization: `Bearer ${token}`,
					"Content-Type": "application/x-tar",
					"Content-Digest": contentDigest,
				},
				// A Blob body carries a Content-Length, which the gateway bounds before reading.
				body: await openAsBlob(archive),
			});
		} catch (error) {
			if (attempt === UPLOAD_ATTEMPTS) {
				throw error;
			}
			console.error(`result upload attempt ${attempt} failed: ${String(error)}`);
			await sleep(UPLOAD_BACKOFF_MS * 2 ** (attempt - 1));
			continue;
		}
		await response.body?.cancel();
		if (response.status === 204 || response.status === 409) {
			if (response.headers.get("etag") === etag) {
				return;
			}
			throw new Error(`Result upload was not confirmed: ${response.status}`);
		}
		if (!retryable(response.status) || attempt === UPLOAD_ATTEMPTS) {
			throw new Error(`Result upload refused: ${response.status}`);
		}
		console.error(`result upload attempt ${attempt} answered ${response.status}`);
		await sleep(UPLOAD_BACKOFF_MS * 2 ** (attempt - 1));
	}
}

async function main() {
	const [command, ...args] = process.argv.slice(2);
	const endpoint = process.env.SANDBOX_RUNTIME_URL;
	const token = process.env.LLM_PROXY_TOKEN;
	if (!present(command) || !present(endpoint) || !present(token)) {
		throw new Error("Command and gateway routing are required");
	}
	const child = spawn(command, args, { stdio: "inherit" });
	process.once("SIGTERM", () => {
		child.kill("SIGTERM");
		setTimeout(() => {
			child.kill("SIGKILL");
		}, CHILD_STOP_GRACE_MS).unref();
	});
	const exitCode = await exited(child);
	const directory = await mkdtemp(path.join(tmpdir(), "gateway-output-"));
	try {
		const archive = path.join(directory, "result.tar");
		if (
			(await run("tar", [
				"--create",
				"--format=ustar",
				"--blocking-factor=1",
				"--file",
				archive,
				"--directory",
				"/workspace",
				"out",
			])) !== 0
		) {
			throw new Error("Could not package sandbox result");
		}
		await upload(new URL(`${endpoint}/result`), token, archive);
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
	process.exitCode = exitCode;
}

if (import.meta.main) {
	try {
		await main();
	} catch (error) {
		console.error(error);
		process.exitCode = 1;
	}
}
