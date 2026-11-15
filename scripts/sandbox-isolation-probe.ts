import { spawn, type ChildProcess } from "node:child_process";
import { createSocket } from "node:dgram";
import { once } from "node:events";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { connect } from "node:net";
import { release } from "node:os";
import { setTimeout as sleep } from "node:timers/promises";

import type { Inside, Target } from "./sandbox-isolation.ts";

// Runs inside the sandbox with the image's Node. A target counts as reached once a TCP connection opens.
// A target that must be reached gets ten seconds, because a new Pod's policy can take a moment to apply;
// one that must not gets three. DNS is one UDP query to the cluster resolver.
const isRecord = (value: unknown): value is Record<string, unknown> =>
	typeof value === "object" && value !== null;
const isTarget = (value: unknown): value is Target =>
	isRecord(value) &&
	typeof value.name === "string" &&
	typeof value.host === "string" &&
	typeof value.port === "number" &&
	typeof value.reachable === "boolean";
const parsed: unknown = JSON.parse(process.argv[1] ?? "");
if (
	!isRecord(parsed) ||
	typeof parsed.dns !== "string" ||
	typeof parsed.firstApi !== "string" ||
	!Array.isArray(parsed.targets) ||
	!parsed.targets.every(isTarget) ||
	(parsed.pidBound !== null && typeof parsed.pidBound !== "number")
) {
	throw new TypeError("the argument is not the probe's spec");
}
const spec = {
	targets: parsed.targets,
	dns: parsed.dns,
	firstApi: parsed.firstApi,
	pidBound: parsed.pidBound,
};

async function reach(host: string, port: number) {
	const socket = connect({ host, port, timeout: 3000 });
	try {
		await Promise.race([
			once(socket, "connect"),
			once(socket, "timeout").then(() => {
				throw new Error("timed out");
			}),
		]);
		return true;
	} catch {
		return false;
	} finally {
		socket.destroy();
	}
}

async function resolve(server: string) {
	if (server === "") {
		return false;
	}
	const socket = createSocket("udp4");
	try {
		const answer = once(socket, "message", { signal: AbortSignal.timeout(3000) });
		socket.send(
			Buffer.from("abcd01000001000000000000076578616d706c6503636f6d0000010001", "hex"),
			53,
			server,
		);
		await answer;
		return true;
	} catch {
		return false;
	} finally {
		socket.close();
	}
}

/** The threads of every process this container sees: what the Pod's PID limit counts. */
function tasks() {
	let sum = 0;
	for (const pid of readdirSync("/proc").filter((name) => /^[0-9]+$/u.test(name))) {
		try {
			sum += Number(
				/^Threads:\s+(?<threads>[0-9]+)/mu.exec(readFileSync(`/proc/${pid}/status`, "utf8"))?.groups
					?.threads ?? 0,
			);
		} catch {
			// The process ended while the probe read it.
		}
	}
	return sum;
}

// Each child starts the given number of libuv pool threads at its first file call, and aborts when the
// kernel refuses one. A child that neither reports nor ends in five seconds counts as refused.
const CHILD = String.raw`require("node:fs").stat("/", () => { process.stdout.write("ready\n"); setInterval(() => {}, 1 << 30); });`;

/** `value` once `event` happens; false when it is aborted first. */
async function outcome(event: Promise<unknown>, value: boolean) {
	try {
		await event;
		return value;
	} catch {
		return false;
	}
}

/** Whether a child with `threads` pool threads started: it reported, rather than ended or stayed silent. */
async function childStarts(threads: number, children: ChildProcess[]) {
	const child = spawn(process.execPath, ["-e", CHILD], {
		env: { UV_THREADPOOL_SIZE: String(threads) },
		stdio: ["ignore", "pipe", "ignore"],
	});
	if (child.pid === undefined) {
		child.on("error", () => undefined);
		return false;
	}
	children.push(child);
	const settled = new AbortController();
	const timer = setTimeout(() => {
		settled.abort();
	}, 5000);
	const ready = outcome(once(child.stdout, "data", { signal: settled.signal }), true);
	const ended = outcome(once(child, "exit", { signal: settled.signal }), false);
	try {
		return await Promise.race([ready, ended]);
	} finally {
		clearTimeout(timer);
		settled.abort();
	}
}

/** Whether the kernel refused a new thread or process before the container held more than `bound` tasks. */
async function pidLimit(bound: number) {
	const children: ChildProcess[] = [];
	let refused = false;
	let held = tasks();
	while (!refused && held <= bound) {
		const threads = Math.max(1, Math.min(1024, bound - held + 1));
		refused = !(await childStarts(threads, children));
		held = tasks();
	}
	await Promise.all(
		children.map(async (child) => {
			if (child.exitCode !== null || child.signalCode !== null) {
				return;
			}
			const exited = once(child, "exit");
			child.kill("SIGKILL");
			await exited;
		}),
	);
	return { refused, held };
}

const status = (key: string) => {
	const line = readFileSync("/proc/self/status", "utf8")
		.split("\n")
		.find((l) => l.startsWith(`${key}:`));
	return line === undefined ? null : line.slice(key.length + 1).trim();
};

const token = existsSync("/var/run/secrets/kubernetes.io/serviceaccount/token");
const serviceAccount = existsSync("/var/run/secrets/kubernetes.io/serviceaccount");
const serviceHosts = Object.keys(process.env)
	.filter((name) => name.endsWith("_SERVICE_HOST"))
	.toSorted();
let uidMap: string | null;
try {
	uidMap = readFileSync("/proc/self/uid_map", "utf8").trim().split(/\s+/u).join(" ");
} catch {
	uidMap = null;
}
const noNewPrivs = status("NoNewPrivs") === "1";
const capEff = status("CapEff");
const kernel = release();
let firstApi: string | null;
try {
	firstApi = readFileSync(spec.firstApi, "utf8");
} catch {
	firstApi = null;
}
const reached: Record<string, boolean> = {};
await Promise.all(
	spec.targets.map(async (target) => {
		const until = Date.now() + (target.reachable ? 10_000 : 0);
		let didReach = await reach(target.host, target.port);
		while (!didReach && Date.now() < until) {
			await sleep(250);
			didReach = await reach(target.host, target.port);
		}
		reached[target.name] = didReach;
	}),
);
const [dns, publicDns] = await Promise.all([resolve(spec.dns), resolve("1.1.1.1")]);
// Last, because no new process or thread starts in the container while the limit is reached.
const pids = spec.pidBound === null ? null : await pidLimit(spec.pidBound);
const report: Inside = {
	uid: process.getuid?.() ?? -1,
	gid: process.getgid?.() ?? -1,
	token,
	serviceAccount,
	serviceHosts,
	uidMap,
	noNewPrivs,
	capEff,
	kernel,
	reached,
	dns,
	publicDns,
	firstApi,
	pids,
};
console.log(JSON.stringify(report));
