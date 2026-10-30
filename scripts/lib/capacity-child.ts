import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { setTimeout as sleep } from "node:timers/promises";

import { asRecord, parseJson } from "./json.ts";

function missing(error: unknown) {
	return (
		error instanceof Error && "code" in error && ["ENOENT", "ESRCH"].includes(String(error.code))
	);
}

async function startTicks(pid: number) {
	const stat = await readFile(`/proc/${pid}/stat`, "utf8");
	return stat.slice(stat.lastIndexOf(")") + 2).split(" ")[19];
}

export async function capacityStopRequested(root: string, label: string) {
	try {
		await readFile(path.join(root, `stop-${label}`));
		return true;
	} catch (error) {
		if (missing(error)) {
			return false;
		}
		throw error;
	}
}

/** Linux process start time prevents a retained receipt from signalling a reused PID. */
export async function recordCapacityChild(root: string, label: string, pid: number) {
	try {
		const ticks = await startTicks(pid);
		if (ticks === undefined) {
			throw new Error("Missing child process start time");
		}
		await writeFile(
			path.join(root, "results", label, "child.json"),
			JSON.stringify({ pid, ticks }),
		);
	} catch (error) {
		if (!missing(error)) {
			throw error;
		}
	}
}

/** The marker covers a stop before the child starts; the identity check covers a live child. */
export async function stopCapacityChild(root: string, label: string) {
	await writeFile(path.join(root, `stop-${label}`), new Date().toISOString());
	try {
		const child = asRecord(
			parseJson(await readFile(path.join(root, "results", label, "child.json"), "utf8")),
			"child",
		);
		if (
			typeof child.pid !== "number" ||
			!Number.isSafeInteger(child.pid) ||
			child.pid < 2 ||
			typeof child.ticks !== "string"
		) {
			throw new Error("Invalid child identity");
		}
		if ((await startTicks(child.pid)) === child.ticks) {
			process.kill(child.pid, "SIGTERM");
			const deadline = Date.now() + 150_000;
			while ((await startTicks(child.pid)) === child.ticks) {
				if (Date.now() >= deadline) {
					throw new Error("Isolated child did not finish cleanup before its stop deadline");
				}
				await sleep(100);
			}
		}
	} catch (error) {
		if (!missing(error)) {
			throw error;
		}
	}
}

/** Keep handlers installed through asynchronous cleanup: a second signal must not terminate it. */
export function installCapacitySignals(abort: AbortController) {
	const stop = () => {
		abort.abort();
	};
	process.on("SIGINT", stop);
	process.on("SIGTERM", stop);
	return () => {
		process.off("SIGINT", stop);
		process.off("SIGTERM", stop);
	};
}
