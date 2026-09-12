import { type ChildProcess, spawn } from "node:child_process";
import { openAsBlob } from "node:fs";
import { mkdtemp, rm } from "node:fs/promises";
import { constants, tmpdir } from "node:os";
import { join } from "node:path";
import { setTimeout as sleep } from "node:timers/promises";

// The worker follows SIGTERM with SIGKILL after its stop timeout; the child gets this much of it and
// the upload of whatever out/ holds by then gets the rest.
const CHILD_STOP_GRACE_MS = 3_000;
const UPLOAD_ATTEMPTS = 3;
const UPLOAD_BACKOFF_MS = 500;

function exitCodeOf(code: number | null, signal: NodeJS.Signals | null): number {
	if (code !== null) return code;
	return 128 + (signal === null ? 0 : constants.signals[signal]);
}

function exited(child: ChildProcess) {
	return new Promise<number>((resolve, reject) => {
		child.once("error", reject);
		child.once("exit", (code, signal) => resolve(exitCodeOf(code, signal)));
	});
}

function run(command: string, args: string[]) {
	return exited(spawn(command, args, { stdio: "inherit" }));
}

function retryable(status: number): boolean {
	return status === 425 || status >= 500;
}

async function upload(url: URL, token: string, archive: string): Promise<void> {
	for (let attempt = 1; ; attempt++) {
		let response: Response;
		try {
			response = await fetch(url, {
				method: "POST",
				headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/x-tar" },
				// A Blob body carries a Content-Length, which the gateway bounds before reading.
				body: await openAsBlob(archive),
			});
		} catch (error) {
			if (attempt === UPLOAD_ATTEMPTS) throw error;
			console.error(`result upload attempt ${attempt} failed: ${String(error)}`);
			await sleep(UPLOAD_BACKOFF_MS * 2 ** (attempt - 1));
			continue;
		}
		await response.body?.cancel();
		if (response.status === 204 || response.status === 409) return;
		if (!retryable(response.status) || attempt === UPLOAD_ATTEMPTS)
			throw new Error(`Result upload refused: ${response.status}`);
		console.error(`result upload attempt ${attempt} answered ${response.status}`);
		await sleep(UPLOAD_BACKOFF_MS * 2 ** (attempt - 1));
	}
}

async function main() {
	const [command, ...args] = process.argv.slice(2);
	const endpoint = process.env.SANDBOX_RUNTIME_URL;
	const token = process.env.LLM_PROXY_TOKEN;
	if (!command || !endpoint || !token) throw new Error("Command and gateway routing are required");
	const child = spawn(command, args, { stdio: "inherit" });
	process.once("SIGTERM", () => {
		child.kill("SIGTERM");
		setTimeout(() => child.kill("SIGKILL"), CHILD_STOP_GRACE_MS).unref();
	});
	const exitCode = await exited(child);
	const directory = await mkdtemp(join(tmpdir(), "gateway-output-"));
	try {
		const archive = join(directory, "result.tar");
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
		)
			throw new Error("Could not package sandbox result");
		await upload(new URL(`${endpoint}/result`), token, archive);
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
	process.exitCode = exitCode;
}

main().catch((error: unknown) => {
	console.error(error);
	process.exitCode = 1;
});
