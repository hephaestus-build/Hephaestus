/**
 * Runs a long command outside the agent's own process limits, and waits for it in bounded steps.
 *
 *   vp run job:start -- <program> [args...]   start it detached; print its id
 *   vp run job:wait <id> [--seconds 540]      block until it ends or the bound passes
 *
 * `job:wait` exits 0 when the command succeeded and 1 when it failed, with the end of its log. It
 * exits 75 (EX_TEMPFAIL) when the command still runs: call it again. The bound stays under the
 * ten-minute limit that agent harnesses put on one foreground command, so an agent keeps its turn
 * and its waits never end with a job it cannot see. A harness background task is killed at its own
 * time limit, and a turn that ends on one may never resume; a detached job survives both.
 *
 * Each job keeps its log and its exit status in `.context/jobs/<id>/`, which Git ignores.
 */
import { spawn } from "node:child_process";
import { randomBytes } from "node:crypto";
import { once } from "node:events";
import {
	closeSync,
	existsSync,
	mkdirSync,
	openSync,
	readFileSync,
	renameSync,
	writeFileSync,
} from "node:fs";
import path from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { parseArgs } from "node:util";

export const STILL_RUNNING = 75;
const DEFAULT_SECONDS = 540;
const jobs = path.join(import.meta.dirname, "..", ".context", "jobs");

const directoryOf = (id: string, root: string): string => {
	if (!/^[a-z0-9-]+$/u.test(id)) {
		throw new Error(`Not a job id: ${id}`);
	}
	return path.join(root, id);
};

/** Starts `command` detached from this process, with its output in the job's log. */
export function start(command: readonly string[], root = jobs, cwd = process.cwd()): string {
	const [program, ...args] = command;
	if (program === undefined) {
		throw new Error("usage: vp run job:start -- <program> [args...]");
	}
	const id = `${new Date().toISOString().replaceAll(/\D/gu, "").slice(0, 14)}-${randomBytes(4).toString("hex")}`;
	const directory = directoryOf(id, root);
	mkdirSync(root, { recursive: true });
	mkdirSync(directory);
	writeFileSync(path.join(directory, "command.json"), `${JSON.stringify(command)}\n`);
	const log = openSync(path.join(directory, "log"), "a");
	// The supervisor outlives this process and records the exit status that `wait` reads.
	const supervisor = spawn(
		process.execPath,
		[import.meta.filename, "supervise", directory, "--", program, ...args],
		{ cwd, detached: true, stdio: ["ignore", log, log] },
	);
	supervisor.unref();
	closeSync(log);
	writeFileSync(path.join(directory, "supervisor.pid"), `${supervisor.pid ?? ""}\n`);
	return id;
}

async function supervise(directory: string, command: readonly string[]): Promise<void> {
	const [program = "", ...args] = command;
	const child = spawn(program, args, { stdio: "inherit" });
	let status: string;
	try {
		await once(child, "close");
		status =
			child.exitCode === null ? `signal ${child.signalCode ?? "unknown"}` : String(child.exitCode);
	} catch (error) {
		status = `error ${error instanceof Error ? error.message : String(error)}`;
	}
	// A rename, so that `wait` never reads a half-written status.
	writeFileSync(path.join(directory, "exit.tmp"), `${status}\n`);
	renameSync(path.join(directory, "exit.tmp"), path.join(directory, "exit"));
}

const tail = (directory: string): string =>
	readFileSync(path.join(directory, "log"), "utf8").split("\n").slice(-40).join("\n");

/** Whether the job's supervisor still runs; a killed one never writes the exit status. */
function supervised(directory: string): boolean {
	const pid = Number(readFileSync(path.join(directory, "supervisor.pid"), "utf8"));
	if (!Number.isInteger(pid) || pid <= 0) {
		return false;
	}
	try {
		process.kill(pid, 0);
		return true;
	} catch (error) {
		// EPERM: the process exists but belongs to another user.
		return error instanceof Error && "code" in error && error.code === "EPERM";
	}
}

export interface Outcome {
	readonly code: 0 | 1 | typeof STILL_RUNNING;
	readonly message: string;
}

/** Waits until the job ends or `seconds` pass, polling its exit file. */
export async function wait(id: string, seconds: number, root = jobs): Promise<Outcome> {
	const directory = directoryOf(id, root);
	if (!existsSync(path.join(directory, "command.json"))) {
		throw new Error(`No job ${id} in ${root}`);
	}
	const exit = path.join(directory, "exit");
	const deadline = Date.now() + seconds * 1000;
	while (!existsSync(exit) && supervised(directory) && Date.now() < deadline) {
		await sleep(Math.min(2000, Math.max(0, deadline - Date.now())));
	}
	if (!existsSync(exit) && !supervised(directory)) {
		return {
			code: 1,
			message: `${tail(directory)}\nJob ${id} lost its supervisor before the command ended. Log: ${path.join(directory, "log")}`,
		};
	}
	if (!existsSync(exit)) {
		return {
			code: STILL_RUNNING,
			message: `Job ${id} still runs after ${seconds} s. Run \`vp run job:wait ${id}\` again.`,
		};
	}
	const status = readFileSync(exit, "utf8").trim();
	if (status === "0") {
		return { code: 0, message: `Job ${id} succeeded. Log: ${path.join(directory, "log")}` };
	}
	return {
		code: 1,
		message: `${tail(directory)}\nJob ${id} failed (${status}). Log: ${path.join(directory, "log")}`,
	};
}

async function main(): Promise<void> {
	const [mode, ...rest] = process.argv.slice(2);
	if (mode === "start") {
		const command = rest[0] === "--" ? rest.slice(1) : rest;
		const id = start(command);
		console.log(`Started job ${id}. Wait for it: vp run job:wait ${id}`);
		return;
	}
	if (mode === "supervise") {
		const [directory = "", separator, ...command] = rest;
		if (separator !== "--") {
			throw new Error("supervise needs a job directory and a command");
		}
		await supervise(directory, command);
		return;
	}
	if (mode === "wait") {
		const { values, positionals } = parseArgs({
			args: rest,
			allowPositionals: true,
			options: { seconds: { type: "string", default: String(DEFAULT_SECONDS) } },
		});
		const seconds = Number(values.seconds);
		const [id] = positionals;
		if (id === undefined || !Number.isFinite(seconds) || seconds < 0) {
			throw new Error("usage: vp run job:wait <id> [--seconds 540]");
		}
		const outcome = await wait(id, seconds);
		console.log(outcome.message);
		process.exitCode = outcome.code;
		return;
	}
	throw new Error("usage: vp run job:start -- <program> [args...] | vp run job:wait <id>");
}

if (import.meta.main) {
	await main().catch((error: unknown) => {
		console.error(error instanceof Error ? error.message : String(error));
		process.exitCode = 2;
	});
}
