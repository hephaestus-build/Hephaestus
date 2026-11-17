import { spawn, spawnSync } from "node:child_process";
import { createWriteStream, existsSync } from "node:fs";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { pipeline } from "node:stream/promises";
import { setTimeout as sleep } from "node:timers/promises";

import {
	discoverCapabilities,
	present,
	fetchGatewayInput,
	recordProgramFailure,
} from "./gateway-capabilities.ts";
import { upload } from "./gateway-run.ts";
import { parseProxyClaim } from "./pi-provider.ts";

/**
 * The exit when the workspace cannot be prepared for a cause that another attempt meets again, once the server has the
 * report of it (SandboxLayout.EXIT_WORKSPACE_UNPREPARED). The server then records it and does not retry. Any other
 * failure, and one whose report did not arrive, exits 1 and is retried.
 */
export const EXIT_WORKSPACE_UNPREPARED = 44;

/**
 * A cause that another attempt meets again: an archive past its budget or without a valid length, one that tar refuses,
 * or a change view that fails. A signal or a process that could not start says nothing about the archive: it retries.
 */
export class WorkspaceUnpreparedError extends Error {
	override name = "WorkspaceUnpreparedError";
}

const DOWNLOAD_ATTEMPTS = 3;
const DOWNLOAD_BACKOFF_MS = 500;

/** Passes a transfer's chunks on and counts their bytes, and fails it once they exceed `budget`. */
async function* withinBudget(
	chunks: AsyncIterable<Uint8Array>,
	budget: number,
	transferred: { bytes: number },
): AsyncGenerator<Uint8Array> {
	for await (const chunk of chunks) {
		transferred.bytes += chunk.length;
		if (transferred.bytes > budget) {
			throw new WorkspaceUnpreparedError("Workspace exceeded its advertised byte budget");
		}
		yield chunk;
	}
}

/** Download a complete archive before extraction, so a broken transfer leaves no partial workspace. */
export async function downloadWorkspace(
	endpoint: string,
	headers: Readonly<Record<string, string>>,
	archive: string,
	signal: AbortSignal,
): Promise<void> {
	const { workspaceByteBudget: budget } = await discoverCapabilities(endpoint, headers, signal);
	for (let attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt += 1) {
		let retryable = true;
		const wait = DOWNLOAD_BACKOFF_MS * 2 ** (attempt - 1);
		try {
			const response = await fetchGatewayInput(`${endpoint}/workspace`, headers, signal);
			if (!response.ok || !response.body) {
				retryable = response.status === 425 || response.status >= 500;
				await response.body?.cancel();
				throw new Error(`Workspace download refused: ${response.status}`);
			}
			const length = response.headers.get("content-length");
			const expected = length !== null && /^[0-9]+$/u.test(length) ? Number(length) : Number.NaN;
			if (!Number.isSafeInteger(expected) || expected <= 0 || expected > budget) {
				retryable = false;
				await response.body.cancel();
				throw new WorkspaceUnpreparedError(
					expected > budget
						? "Workspace exceeded its advertised byte budget"
						: "Workspace requires a valid Content-Length",
				);
			}
			const transferred = { bytes: 0 };
			try {
				await pipeline(
					response.body,
					(chunks: AsyncIterable<Uint8Array>) => withinBudget(chunks, budget, transferred),
					createWriteStream(archive),
					{ signal },
				);
			} catch (error) {
				if (transferred.bytes > budget) {
					retryable = false;
					throw new WorkspaceUnpreparedError("Workspace exceeded its advertised byte budget", {
						cause: error,
					});
				}
				throw error;
			}
			if (transferred.bytes !== expected) {
				throw new Error("Incomplete workspace download");
			}
			return;
		} catch (error) {
			if (!retryable || attempt === DOWNLOAD_ATTEMPTS) {
				throw error;
			}
			console.error(`workspace download attempt ${attempt} failed: ${String(error)}`);
			await sleep(wait, undefined, { signal });
		}
	}
}

/**
 * The volumes mounted below /workspace (KubernetesPods.attemptVolumes; tmpfs mounts on Docker). On Kubernetes each is
 * an emptyDir mount point that root owns: the sandbox user may write into it but not change its mode or times.
 */
export const MOUNT_POINTS = ["work", "out", ".pi", ".sessions"];

/**
 * GNU tar's arguments for extracting the workspace into a directory. The archive's entries for the mount points
 * themselves are skipped, so tar never sets their mode or times; everything below them extracts as before.
 * `--no-recursion` keeps an excluded directory entry from excluding its contents. `--no-overwrite-dir` does not
 * help: tar still changes an existing directory's mode while it writes into it.
 */
export function extractArguments(
	archive: string,
	directory: string,
	mountPoints: readonly string[] = MOUNT_POINTS,
): string[] {
	return [
		"--extract",
		"--file",
		archive,
		"--directory",
		directory,
		"--no-same-owner",
		"--no-same-permissions",
		"--no-recursion",
		"--anchored",
		...mountPoints.map((name) => `--exclude=${name}`),
		"--delay-directory-restore",
	];
}

export async function extract(
	archive: string,
	directory = "/workspace",
	mountPoints: readonly string[] = MOUNT_POINTS,
): Promise<void> {
	const child = spawn("tar", extractArguments(archive, directory, mountPoints), {
		stdio: "inherit",
	});
	const extracted = Promise.withResolvers<undefined>();
	child.once("error", extracted.reject);
	child.once("exit", (code, signal) => {
		if (code === 0) {
			extracted.resolve(undefined);
		} else if (code === null) {
			extracted.reject(new Error(`Workspace extraction stopped: ${signal ?? "no exit status"}`));
		} else {
			extracted.reject(new WorkspaceUnpreparedError(`Workspace extraction failed: ${code}`));
		}
	});
	await extracted.promise;
}

/** Staged for a review; a host has none. */
const CHANGE_VIEW = "/workspace/pi-change-view.js";

/** What the change view may see of this process's environment: Git's settings, and no credential. */
export function changeViewEnvironment(env: NodeJS.ProcessEnv): NodeJS.ProcessEnv {
	return Object.fromEntries(
		Object.entries(env).filter(
			([name]) => ["PATH", "HOME", "LANG", "TMPDIR"].includes(name) || name.startsWith("GIT_"),
		),
	);
}

/** Runs before the loop or the tools start: see pi-change-view.ts. A failure here is a failure to capture. */
function deriveChangeView(): void {
	if (!existsSync(CHANGE_VIEW)) {
		return;
	}
	const view = spawnSync("node", [CHANGE_VIEW], {
		cwd: "/workspace",
		env: changeViewEnvironment(process.env),
		stdio: "inherit",
	});
	if (view.status === null) {
		throw new Error(`The change view stopped: ${String(view.error ?? view.signal)}`);
	}
	if (view.status !== 0) {
		throw new WorkspaceUnpreparedError(`The change view failed: ${String(view.status)}`);
	}
}

/** The run's claim, its gateway and its deadline: without them nothing can report a failure, so the attempt retries. */
function runInputs() {
	const claim = parseProxyClaim(process.env.LLM_PROXY_CLAIM);
	const endpoint = process.env.SANDBOX_RUNTIME_URL;
	const token = process.env.LLM_PROXY_TOKEN;
	if (!present(endpoint) || !present(token)) {
		throw new Error("Gateway credential and runtime endpoint are required");
	}
	const workDeadline = Number(process.env.SANDBOX_WORK_DEADLINE_MS);
	if (!Number.isSafeInteger(workDeadline) || workDeadline <= 0) {
		throw new Error("The workspace requires its admitted work deadline");
	}
	return { claim, endpoint, token, workDeadline };
}

/** Two zero blocks: an empty tar archive, the result of an attempt that measured nothing. */
const EMPTY_ARCHIVE = Buffer.alloc(1024);

/**
 * Reports a workspace that no attempt can prepare as the attempt's result with its exit code, on the route that a
 * measured result takes, so that the server records it before the sandbox ends the run.
 */
export async function reportUnprepared(
	endpoint: string,
	token: string,
	claim: Readonly<Record<string, string>>,
	uploadDeadline: number,
): Promise<void> {
	const directory = await mkdtemp(path.join(tmpdir(), "gateway-unprepared-"));
	try {
		const archive = path.join(directory, "result.tar");
		await writeFile(archive, EMPTY_ARCHIVE);
		await upload(new URL(`${endpoint}/result`), token, archive, uploadDeadline, undefined, {
			...claim,
			"x-sandbox-exit-code": String(EXIT_WORKSPACE_UNPREPARED),
			"x-sandbox-timed-out": "false",
		});
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
}

async function main(): Promise<number> {
	const { claim, endpoint, token, workDeadline } = runInputs();
	try {
		await prepare(claim, endpoint, token, workDeadline);
		return 0;
	} catch (error) {
		if (!(error instanceof WorkspaceUnpreparedError)) {
			throw error;
		}
		console.error(error);
		const uploadDeadline = Number(process.env.SANDBOX_UPLOAD_DEADLINE_MS);
		await reportUnprepared(
			endpoint,
			token,
			claim,
			Number.isSafeInteger(uploadDeadline) ? uploadDeadline : workDeadline,
		);
		return EXIT_WORKSPACE_UNPREPARED;
	}
}

async function prepare(
	claim: Readonly<Record<string, string>>,
	endpoint: string,
	token: string,
	workDeadline: number,
): Promise<void> {
	const signal = AbortSignal.timeout(Math.max(0, workDeadline - Date.now()));
	const directory = await mkdtemp("/workspace/.gateway-download-");
	try {
		const archive = path.join(directory, "workspace.tar");
		await downloadWorkspace(
			endpoint,
			{ Authorization: `Bearer ${token}`, ...claim },
			archive,
			signal,
		);
		await extract(archive);
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
	deriveChangeView();
}

if (import.meta.main) {
	try {
		process.exitCode = await main();
	} catch (error) {
		// Also a cause no attempt meets again whose report did not arrive: the server then retries the attempt.
		console.error(error);
		recordProgramFailure("init", error);
		process.exitCode = 1;
	}
}
