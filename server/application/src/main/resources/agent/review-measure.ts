// review-measure.ts — a review's measurement in its own sandbox: the driver's REVIEW kind.
//
// The image keeps the programs that a review Job's Pod ran, and the kind runs them in the same order, in one
// container: the initializer downloads and extracts the captured workspace, the tools side serves the runner's
// commands on a Unix socket, and the run program starts the runner under its work deadline and uploads what it wrote.
// The server measures the result when it arrives, so the run is done when its upload is confirmed.

import { type ChildProcess, spawn } from "node:child_process";
import { randomBytes } from "node:crypto";
import { existsSync, mkdirSync } from "node:fs";
import { setTimeout as sleep } from "node:timers/promises";

import type { KindRunner, RunContext, RunOutcome } from "./driver.ts";

/** Where the image keeps the review programs (docker/agents/pi). */
const SDK = "/opt/pi-sdk";
const SOCKETS = "/tmp/review-sides";
const SIDE_START_MS = 10_000;
/** gateway-run.ts's exit after it could not upload the result. */
const EXIT_UPLOAD_FAILED = 43;

/** What the server gives a measurement: the runner it starts, and when its work and its upload end. */
export interface MeasureContext {
	command: string[];
	env: Record<string, string>;
	/** In epoch milliseconds. */
	workDeadline: number;
	uploadDeadline: number;
	/** Whether a custom practice program runs, on a side of its own. */
	programs: boolean;
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
	const { command, env, workDeadline, uploadDeadline, programs } = value;
	if (
		!isCommand(command) ||
		!isStringRecord(env) ||
		typeof workDeadline !== "number" ||
		typeof uploadDeadline !== "number" ||
		!Number.isSafeInteger(workDeadline) ||
		!Number.isSafeInteger(uploadDeadline) ||
		uploadDeadline <= workDeadline ||
		typeof programs !== "boolean"
	) {
		throw new Error(
			"the measurement context does not name a runner, its environment and its deadlines",
		);
	}
	return { command, env, workDeadline, uploadDeadline, programs };
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

/** A side that serves the runner on a Unix socket of its own, as the review Pod's sidecar did. */
async function startSide(role: "tools" | "programs", nonce: string, env: Record<string, string>) {
	const socket = `${SOCKETS}/${role}.sock`;
	const gitSettings = Object.fromEntries(
		Object.entries(env).filter(([name]) => name.startsWith("GIT_")),
	);
	const side = spawn("sh", [`${SDK}/tools-launch.sh`], {
		env: {
			PATH: env.PATH ?? "",
			...gitSettings,
			TOOLS_NONCE: nonce,
			TOOLS_SOCKET: socket,
			TOOLS_ROLE: role,
		},
		stdio: "inherit",
	});
	const until = Date.now() + SIDE_START_MS;
	while (!existsSync(socket)) {
		if (side.exitCode !== null || Date.now() > until) {
			side.kill("SIGKILL");
			throw new Error(`the ${role} side did not start`);
		}
		await sleep(50);
	}
	return { socket, side };
}

/** The REVIEW kind: it measures the review, and the server admits what the upload brings. */
export const measureReview: KindRunner = async (run): Promise<RunOutcome> => {
	const { signal } = run;
	const base = `${run.gateway.url}/internal/agent/runs/${run.assignment.runId}`;
	const answer = await fetch(`${base}/context`, { headers: run.gateway.headers, signal });
	if (!answer.ok) {
		await answer.body?.cancel();
		throw new Error(`the measurement context answered ${String(answer.status)}`);
	}
	const context = parseMeasureContext(await answer.json());
	const env = measureEnvironment(run, context);
	if ((await runToEnd("node", [`${SDK}/gateway-init.js`], env, signal)) !== 0) {
		throw new Error("the workspace could not be prepared");
	}
	mkdirSync(SOCKETS, { recursive: true, mode: 0o700 });
	const nonce = randomBytes(32).toString("hex");
	const tools = await startSide("tools", nonce, env);
	const sides = [tools];
	try {
		const loopEnv: Record<string, string> = {
			...env,
			TOOLS_SOCKET: tools.socket,
			TOOLS_NONCE: nonce,
		};
		if (context.programs) {
			const programs = await startSide("programs", nonce, env);
			sides.push(programs);
			loopEnv.PROGRAMS_SOCKET = programs.socket;
		}
		const exit = await runToEnd(
			"node",
			[`${SDK}/gateway-run.js`, ...context.command],
			loopEnv,
			signal,
		);
		if (signal.aborted) {
			return { leaseLost: true };
		}
		// The run program uploads whatever the runner wrote, however it ended; the server measures it.
		return exit === EXIT_UPLOAD_FAILED
			? { leaseLost: false, outcome: "handed_on" }
			: { leaseLost: false, outcome: "completed" };
	} finally {
		for (const { side } of sides) {
			side.kill("SIGTERM");
		}
	}
};
