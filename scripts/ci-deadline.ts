/**
 * Runs a `vp` command with a deadline under its job's time limit: `node scripts/ci-deadline.ts <seconds> vp <arguments...>`.
 * A job that reaches its limit is cancelled, and its log can be lost. So at the deadline this lists every
 * process of the task with its elapsed time and command line, on stderr and in the job summary, then
 * stops them and exits 124. The list names the task that hung.
 */
import { spawn, spawnSync } from "node:child_process";
import { once } from "node:events";
import { appendFileSync } from "node:fs";
import { constants } from "node:os";
import { setTimeout } from "node:timers/promises";

import { repositoryCli } from "./lib/process.ts";

export interface ProcessRow {
	pid: number;
	ppid: number;
	elapsed: string;
	command: string;
}

/** `root` and every process below it, from rows of the whole process table. */
export function descendants(rows: readonly ProcessRow[], root: number): ProcessRow[] {
	const children = new Map<number, ProcessRow[]>();
	for (const row of rows) {
		children.set(row.ppid, [...(children.get(row.ppid) ?? []), row]);
	}
	const found = rows.filter((row) => row.pid === root);
	// The array grows while it is walked, so each child's own children are visited too.
	for (const row of found) {
		for (const child of children.get(row.pid) ?? []) {
			if (!found.includes(child)) {
				found.push(child);
			}
		}
	}
	return found.toSorted((a, b) => a.pid - b.pid);
}

function processTable(): ProcessRow[] {
	if (process.platform === "win32") {
		const listed = spawnSync(
			"powershell",
			[
				"-NoProfile",
				"-Command",
				'Get-CimInstance Win32_Process | ForEach-Object { "$($_.ProcessId)`t$($_.ParentProcessId)`t$($_.CreationDate)`t$($_.CommandLine)" }',
			],
			{ encoding: "utf8", maxBuffer: 16 * 1024 * 1024 },
		);
		return windowsRows(listed.stdout);
	}
	const listed = spawnSync("ps", ["-A", "-o", "pid=,ppid=,etime=,args="], {
		encoding: "utf8",
		maxBuffer: 16 * 1024 * 1024,
	});
	return listed.stdout.split("\n").flatMap((line) => {
		const { pid, ppid, elapsed, command } =
			/^\s*(?<pid>\d+)\s+(?<ppid>\d+)\s+(?<elapsed>\S+)\s+(?<command>.*)$/u.exec(line)?.groups ??
			{};
		return pid === undefined || ppid === undefined || elapsed === undefined || command === undefined
			? []
			: [{ pid: Number(pid), ppid: Number(ppid), elapsed, command }];
	});
}

function windowsRows(text: string): ProcessRow[] {
	return text.split(/\r?\n/u).flatMap((line) => {
		const [pid, ppid, started, ...command] = line.split("\t");
		return pid === undefined || ppid === undefined || !/^\d+$/u.test(pid)
			? []
			: [
					{
						pid: Number(pid),
						ppid: Number(ppid),
						elapsed: `since ${started ?? "?"}`,
						command: command.join("\t"),
					},
				];
	});
}

/** The processes of `running` that have no running child: the commands that the task waits on. */
export function innermost(running: readonly ProcessRow[]): ProcessRow[] {
	return running.filter((row) => !running.some((other) => other.ppid === row.pid));
}

/**
 * The report of what still ran at the deadline. Its first line names the innermost commands, because an
 * annotation shows that line first; the table lists every process.
 */
export function deadlineReport(seconds: number, running: readonly ProcessRow[]): string {
	const waiting = innermost(running);
	const headline =
		waiting.length === 0
			? `The step reached its deadline of ${seconds} s, under the job's time limit, and no process of its task was found. The task may have finished its work without exiting, or the process table could not be read.`
			: `The step reached its deadline of ${seconds} s, under the job's time limit. Still running: ${waiting.map(({ elapsed, command }) => `${command.slice(0, 160)} (${elapsed})`).join("; ")}`;
	const lines = running.map(
		({ pid, elapsed, command }) =>
			`${String(pid).padStart(7)}  ${elapsed.padStart(11)}  ${command.slice(0, 240)}`,
	);
	return [headline, "", "    pid      elapsed  command", ...lines].join("\n");
}

/** A workflow command's message: GitHub reads `%`, carriage return and newline only escaped. */
export function annotation(message: string): string {
	return message.replaceAll("%", "%25").replaceAll("\r", "%0D").replaceAll("\n", "%0A");
}

/** Runs the repository command with a deadline that includes its startup. */
export async function runWithDeadline(seconds: number, args: string[]): Promise<number> {
	const posix = process.platform !== "win32";
	const child = spawn(process.execPath, [repositoryCli(), ...args], {
		stdio: "inherit",
		detached: posix,
	});
	const stop = (name: NodeJS.Signals) => {
		if (child.pid === undefined) {
			return;
		}
		if (!posix) {
			spawnSync("taskkill", ["/pid", String(child.pid), "/T", "/F"], { stdio: "ignore" });
			return;
		}
		try {
			process.kill(-child.pid, name);
		} catch {
			// Every process of the group already ended.
		}
	};
	// The runner cancels a step with SIGINT, then SIGTERM; its own process group does not get them.
	const onInterrupt = () => stop("SIGINT");
	const onTerminate = () => stop("SIGTERM");
	process.on("SIGINT", onInterrupt).on("SIGTERM", onTerminate);
	const state = { late: false, listed: [] as number[] };
	let force: ReturnType<typeof globalThis.setTimeout> | undefined;
	const deadline = globalThis.setTimeout(() => {
		state.late = true;
		const running = child.pid === undefined ? [] : descendants(processTable(), child.pid);
		state.listed = running.map(({ pid }) => pid);
		const report = deadlineReport(seconds, running);
		// The whole report in the annotation: the checks page shows annotations, and a cancelled job's log can be lost.
		process.stderr.write(
			`\n::error title=Quality gates hit their deadline::${annotation(report)}\n${report}\n`,
		);
		const summary = process.env.GITHUB_STEP_SUMMARY;
		if (summary !== undefined && summary !== "") {
			appendFileSync(
				summary,
				`\n### Quality gates hit their deadline\n\n\`\`\`\n${report}\n\`\`\`\n`,
			);
		}
		// A task runner can start its commands outside this process group, so each listed process too.
		stop("SIGTERM");
		signalEach(state.listed, "SIGTERM");
		// A task that ignores SIGTERM must not hold the step until the job limit.
		force = globalThis.setTimeout(() => {
			stop("SIGKILL");
			signalEach(state.listed.filter(alive), "SIGKILL");
		}, 10_000);
	}, seconds * 1000);
	try {
		await once(child, "close");
		if (state.late) {
			// Nothing of the task may outlive the step: what has not ended within 10 s is killed.
			for (let waited = 0; waited < 10_000 && state.listed.some(alive); waited += 250) {
				await setTimeout(250);
			}
			stop("SIGKILL");
			signalEach(state.listed.filter(alive), "SIGKILL");
		}
		return state.late ? 124 : exitStatus(child.exitCode, child.signalCode);
	} finally {
		clearTimeout(deadline);
		clearTimeout(force);
		process.off("SIGINT", onInterrupt).off("SIGTERM", onTerminate);
	}
}

/** The status a shell reports: the exit code, or 128 plus the number of the signal that ended it. */
export function exitStatus(code: number | null, signal: NodeJS.Signals | null): number {
	if (code !== null) {
		return code;
	}
	return signal === null ? 1 : 128 + constants.signals[signal];
}

function alive(pid: number): boolean {
	try {
		process.kill(pid, 0);
		return true;
	} catch {
		return false;
	}
}

function signalEach(pids: readonly number[], name: NodeJS.Signals): void {
	for (const pid of pids) {
		try {
			process.kill(pid, name);
		} catch {
			// It already ended.
		}
	}
}

if (import.meta.main) {
	const [limit, program, ...args] = process.argv.slice(2);
	const seconds = Number(limit);
	// The command stays readable as `vp run <task>` in the workflow, where the CI contract finds it.
	if (!Number.isInteger(seconds) || seconds <= 0 || program !== "vp" || args.length === 0) {
		throw new Error("usage: node scripts/ci-deadline.ts <seconds> vp <arguments...>");
	}
	process.exitCode = await runWithDeadline(seconds, args);
}
