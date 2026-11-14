// review-measure.ts — a review's measurement in its own sandbox: the driver's REVIEW kind.
//
// The kind runs the image's review programs in order, in one container (gateway-init.ts, gateway-run.ts): the
// initializer downloads and extracts the captured workspace, then the run program starts the runner
// under its work deadline and uploads what it wrote. The runner starts tools and programs in this same container.
// The server measures the result when it arrives, so the run is done when its upload is confirmed.

import { type ChildProcess, spawn } from "node:child_process";
import { existsSync, symlinkSync } from "node:fs";
import { chmod, lstat, readdir, rm } from "node:fs/promises";
import path from "node:path";
import { setTimeout as sleep } from "node:timers/promises";

import type { KindRunner, RunContext, RunOutcome } from "./driver.ts";

/** Where the image keeps the review programs and the Pi SDK. */
const SDK = "/opt/pi-sdk";
/** The sandbox's /workspace volume, over the image's read-only root. */
const WORKSPACE = "/workspace";
/**
 * The runner at /workspace/.run-pi.js resolves the Pi SDK through this link, so the kind makes it before the workspace
 * fills.
 */
const SDK_LINK = "/workspace/node_modules";
/** gateway-run.ts's exit after it could not upload the result. */
const EXIT_UPLOAD_FAILED = 43;

/** What the server gives a measurement: the runner it starts, and when its work and its upload end. */
export interface MeasureContext {
	command: string[];
	env: Record<string, string>;
	/** In epoch milliseconds. */
	workDeadline: number;
	uploadDeadline: number;
}

function isJsonObject(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isCommand(value: unknown): value is string[] {
	return (
		Array.isArray(value) &&
		value.length > 0 &&
		value.every((part) => typeof part === "string" && part !== "")
	);
}

function isStringRecord(value: unknown): value is Record<string, string> {
	return (
		typeof value === "object" &&
		value !== null &&
		!Array.isArray(value) &&
		Object.values(value).every((entry) => typeof entry === "string")
	);
}

/** The server's answer, checked: a measurement that the kind cannot read is no measurement to start. */
export function parseMeasureContext(value: unknown): MeasureContext {
	if (!isJsonObject(value)) {
		throw new Error("the measurement context is not an object");
	}
	const { command, env, workDeadline, uploadDeadline } = value;
	if (
		!isCommand(command) ||
		!isStringRecord(env) ||
		typeof workDeadline !== "number" ||
		typeof uploadDeadline !== "number" ||
		!Number.isSafeInteger(workDeadline) ||
		!Number.isSafeInteger(uploadDeadline) ||
		uploadDeadline <= workDeadline
	) {
		throw new Error(
			"the measurement context does not name a runner, its environment and its deadlines",
		);
	}
	return { command, env, workDeadline, uploadDeadline };
}

/** The environment that each program of the measurement gets: the server's, the gateway's routes and the claim. */
export function measureEnvironment(
	run: RunContext,
	context: MeasureContext,
): Record<string, string> {
	const { authorization = "", ...claim } = run.gateway.headers;
	return {
		...context.env,
		PATH: process.env.PATH ?? "/usr/local/bin:/usr/bin:/bin",
		LLM_PROXY_URL: `${run.gateway.url}/internal/llm`,
		LLM_PROXY_TOKEN: authorization.replace(/^Bearer /u, ""),
		LLM_PROXY_CLAIM: JSON.stringify(claim),
		SANDBOX_RUNTIME_URL: `${run.gateway.url}/internal/agent/runs/${run.assignment.runId}/runtime`,
		SANDBOX_WORK_DEADLINE_MS: String(context.workDeadline),
		SANDBOX_UPLOAD_DEADLINE_MS: String(context.uploadDeadline),
	};
}

async function exitOf(child: ChildProcess): Promise<number> {
	const exited = Promise.withResolvers<number>();
	child.once("error", exited.reject);
	child.once("exit", (code, signal) => {
		exited.resolve(code ?? (signal === null ? 1 : 128));
	});
	return exited.promise;
}

/** Runs one program to its end, or stops it when the run's signal aborts. */
async function runToEnd(
	command: string,
	args: string[],
	env: Record<string, string>,
	signal: AbortSignal,
) {
	const child = spawn(command, args, { env, stdio: "inherit" });
	const stop = () => {
		child.kill("SIGTERM");
	};
	signal.addEventListener("abort", stop, { once: true });
	try {
		return await exitOf(child);
	} finally {
		signal.removeEventListener("abort", stop);
	}
}

/**
 * Empties a directory. Its captured tree has read-only directories, which neither tar nor a removal can write into, so
 * each directory is made writable before its entries go.
 */
export async function emptyDirectory(directory: string): Promise<void> {
	for (const entry of await readdir(directory)) {
		await removeTree(path.join(directory, entry));
	}
}

async function removeTree(target: string): Promise<void> {
	const entry = await lstat(target);
	if (entry.isDirectory()) {
		await chmod(target, 0o700);
		await emptyDirectory(target);
	}
	await rm(target, { recursive: true, force: true });
}

/** The descriptor GET is replayable; a busy or restarting replica must not hand a still-leased run back. */
export async function readMeasureContext(
	run: RunContext,
	transport: typeof globalThis.fetch = globalThis.fetch,
): Promise<MeasureContext | null> {
	const { signal } = run;
	const base = `${run.gateway.url}/internal/agent/runs/${run.assignment.runId}`;
	for (;;) {
		signal.throwIfAborted();
		try {
			const answer = await transport(`${base}/context`, { headers: run.gateway.headers, signal });
			if (answer.status === 409) {
				await answer.body?.cancel();
				return null;
			}
			if ([425, 429, 503].includes(answer.status)) {
				const retryAfter = answer.headers.get("retry-after");
				const seconds = retryAfter === null ? Number.NaN : Number(retryAfter);
				await answer.body?.cancel();
				await sleep(Number.isFinite(seconds) && seconds >= 0 ? seconds * 1000 : 1000, undefined, {
					signal,
				});
				continue;
			}
			if (!answer.ok) {
				await answer.body?.cancel();
				throw new Error(`the measurement context answered ${String(answer.status)}`);
			}
			return parseMeasureContext(await answer.json());
		} catch (error) {
			if (signal.aborted || !(error instanceof TypeError)) {
				throw error;
			}
			await sleep(1000, undefined, { signal });
		}
	}
}

/** The REVIEW kind: it measures the review, and the server admits what the upload brings. */
export const measureReview: KindRunner = async (run): Promise<RunOutcome> => {
	const { signal } = run;
	const context = await readMeasureContext(run);
	if (context === null) {
		return { leaseLost: true };
	}
	const env = measureEnvironment(run, context);
	// A sandbox also serves its review's next attempt. Each attempt's /workspace starts from its own captured workspace,
	// never from what an earlier attempt extracted or wrote there.
	await emptyDirectory(WORKSPACE);
	if (!existsSync(SDK_LINK)) {
		symlinkSync(`${SDK}/node_modules`, SDK_LINK);
	}
	if ((await runToEnd("node", [`${SDK}/gateway-init.js`], env, signal)) !== 0) {
		throw new Error("the workspace could not be prepared");
	}
	const exit = await runToEnd("node", [`${SDK}/gateway-run.js`, ...context.command], env, signal);
	if (signal.aborted) {
		return { leaseLost: true };
	}
	// The run program uploads whatever the runner wrote. The server admits it against its own evidence.
	return exit === EXIT_UPLOAD_FAILED
		? { leaseLost: false, outcome: "handed_on" }
		: { leaseLost: false, outcome: "completed" };
};
