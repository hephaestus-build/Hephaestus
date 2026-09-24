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
const UPLOAD_BACKOFF_MS = 500;
const MAX_UPLOAD_BACKOFF_MS = 30_000;
const UPLOAD_GRACE_MS = 10 * 60_000;
const EXIT_UPLOAD_FAILED = 43;
const EXIT_WORK_TIMEOUT = 124;

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

async function waitBeforeRetry(attempt: number, deadline: number): Promise<void> {
	const delay = Math.min(
		MAX_UPLOAD_BACKOFF_MS,
		UPLOAD_BACKOFF_MS * 2 ** Math.min(attempt - 1, 16),
		Math.max(0, deadline - Date.now()),
	);
	await sleep(delay);
}

export async function upload(
	url: URL,
	token: string,
	archive: string,
	deadline = Date.now() + UPLOAD_GRACE_MS,
): Promise<void> {
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
		const remaining = deadline - Date.now();
		if (remaining <= 0) {
			throw new Error("Result upload deadline expired");
		}
		let response: Response;
		try {
			response = await fetch(url, {
				method: "POST",
				signal: AbortSignal.timeout(remaining),
				headers: {
					Authorization: `Bearer ${token}`,
					"Content-Type": "application/x-tar",
					"Content-Digest": contentDigest,
				},
				// A Blob body carries a Content-Length, which the gateway bounds before reading.
				body: await openAsBlob(archive),
			});
		} catch (error) {
			console.error(`result upload attempt ${attempt} failed: ${String(error)}`);
			await waitBeforeRetry(attempt, deadline);
			continue;
		}
		await response.body?.cancel();
		if (response.status === 204 || response.status === 409) {
			if (response.headers.get("etag") === etag) {
				return;
			}
			throw new Error(`Result upload was not confirmed: ${response.status}`);
		}
		if (!retryable(response.status)) {
			throw new Error(`Result upload refused: ${response.status}`);
		}
		console.error(`result upload attempt ${attempt} answered ${response.status}`);
		await waitBeforeRetry(attempt, deadline);
	}
}

async function main() {
	const [command, ...args] = process.argv.slice(2);
	const endpoint = process.env.SANDBOX_RUNTIME_URL;
	const token = process.env.LLM_PROXY_TOKEN;
	if (!present(command) || !present(endpoint) || !present(token)) {
		throw new Error("Command and gateway routing are required");
	}
	const workDeadline = Number(process.env.SANDBOX_WORK_DEADLINE_MS);
	const uploadDeadline = Number(process.env.SANDBOX_UPLOAD_DEADLINE_MS);
	if (
		!Number.isSafeInteger(workDeadline) ||
		!Number.isSafeInteger(uploadDeadline) ||
		uploadDeadline <= workDeadline
	) {
		throw new Error("Valid sandbox work and upload deadlines are required");
	}
	const child = spawn(command, args, { stdio: "inherit" });
	const workTimeout = new AbortController();
	const deadlineTimer = setTimeout(
		() => {
			workTimeout.abort();
			child.kill("SIGTERM");
			setTimeout(() => {
				child.kill("SIGKILL");
			}, CHILD_STOP_GRACE_MS).unref();
		},
		Math.max(0, workDeadline - Date.now()),
	);
	deadlineTimer.unref();
	process.once("SIGTERM", () => {
		child.kill("SIGTERM");
		setTimeout(() => {
			child.kill("SIGKILL");
		}, CHILD_STOP_GRACE_MS).unref();
	});
	const exitCode = await exited(child);
	clearTimeout(deadlineTimer);
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
		try {
			await upload(new URL(`${endpoint}/result`), token, archive, uploadDeadline);
		} catch (error) {
			console.error(error);
			process.exitCode = EXIT_UPLOAD_FAILED;
			return;
		}
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
	process.exitCode = workTimeout.signal.aborted ? EXIT_WORK_TIMEOUT : exitCode;
}

if (import.meta.main) {
	try {
		await main();
	} catch (error) {
		console.error(error);
		process.exitCode = 1;
	}
}
