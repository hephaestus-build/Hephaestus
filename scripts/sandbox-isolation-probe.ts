import { createSocket } from "node:dgram";
import { once } from "node:events";
import { existsSync, readFileSync } from "node:fs";
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
	!parsed.targets.every(isTarget)
) {
	throw new TypeError("the argument is not the probe's spec");
}
const spec = { targets: parsed.targets, dns: parsed.dns, firstApi: parsed.firstApi };

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
};
console.log(JSON.stringify(report));
