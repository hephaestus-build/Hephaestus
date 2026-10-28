// Tool protocol v1 end to end: the loop's client against the tools program on a real Unix socket, and against a hostile
// tools side that breaks the protocol.

import assert from "node:assert/strict";
import { execFileSync, spawn, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { once } from "node:events";
import {
	existsSync,
	mkdtempSync,
	mkdirSync,
	readdirSync,
	readFileSync,
	realpathSync,
	rmSync,
	statSync,
	symlinkSync,
	writeFileSync,
} from "node:fs";
import fs from "node:fs/promises";
import { syncBuiltinESMExports } from "node:module";
import { connect, createServer, type Server, type Socket } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import test, { after, before, beforeEach, type TestContext } from "node:test";
import { setTimeout as sleep } from "node:timers/promises";
import { fileURLToPath } from "node:url";

import { type PutArchive, ToolClient } from "../../../main/resources/agent/tool-client.ts";
import {
	encode,
	frameReader,
	type Header,
	LIMITS,
	ToolProtocolError,
} from "../../../main/resources/agent/tool-protocol.ts";
import { checkAuthority, startToolsServer } from "../../../main/resources/agent/tools-server.ts";

const base = mkdtempSync(path.join(tmpdir(), "tools-"));
const workspace = path.join(base, "workspace");
const scratch = path.join(workspace, ".scratch");
const outside = path.join(base, "outside");
execFileSync("mkdir", ["-p", path.join(workspace, "repos"), scratch, outside]);
writeFileSync(path.join(outside, "kept.txt"), "kept\n");
const socketPath = path.join(base, "tools.sock");
const nonce = "pod-nonce";
const soleUser = process.env.TOOLS_TEST_SOLE_USER === "true";

function trashDirectories(): string[] {
	return readdirSync(base).filter((name) => name.startsWith(".tools-trash-"));
}

async function awaitTrashRemoval(): Promise<void> {
	for (let attempt = 0; trashDirectories().length > 0 && attempt < 100; attempt += 1) {
		await sleep(10);
	}
	assert.equal(trashDirectories().length, 0);
}

let server: Server;
let client: ToolClient;
let openedAt = 0;

before(async () => {
	if (soleUser) {
		assert.equal(process.ppid, 1, "sole-user process tests require a private container init");
		checkAuthority();
	}
	process.env.HEPHAESTUS_SECRET = "must-not-leak";
	server = startToolsServer({
		socketPath,
		nonce,
		workspace,
		roots: [workspace, scratch],
		scratch,
		putRoots: [path.join(workspace, "repos")],
		soleUser,
	});
	await once(server, "listening");
	client = await ToolClient.open(socketPath, nonce);
	openedAt = Date.now();
});

beforeEach(async () => {
	await awaitTrashRemoval();
	mkdirSync(path.join(workspace, "repos"), { recursive: true });
	mkdirSync(scratch, { recursive: true });
	writeFileSync(path.join(workspace, "notes.txt"), "one\ntwo\nthree\nfour\n");
	if (!existsSync(path.join(workspace, "escape"))) {
		symlinkSync("/etc", path.join(workspace, "escape"));
	}
});

after(() => {
	client.close();
	server.close();
});

async function run(
	command: string,
	extra: { timeoutMs?: number; maxOutputBytes?: number } = {},
	signal?: AbortSignal,
) {
	const chunks: Buffer[] = [];
	const exit = await client.exec(
		{
			command,
			cwd: workspace,
			timeoutMs: extra.timeoutMs ?? 10_000,
			maxOutputBytes: extra.maxOutputBytes,
		},
		(chunk) => {
			chunks.push(chunk);
		},
		signal,
	);
	return { exit, output: Buffer.concat(chunks).toString("utf8") };
}

function alive(pid: number): boolean {
	try {
		process.kill(pid, 0);
		return true;
	} catch {
		return false;
	}
}

function refusal(code: string) {
	return (error: unknown) => error instanceof ToolProtocolError && error.code === code;
}

/** A put archive of `bytes`, streamed in chunks smaller than a frame body. */
function archiveOf(bytes: Buffer): PutArchive {
	async function* chunks() {
		for (let at = 0; at < bytes.length; at += 300_000) {
			yield bytes.subarray(at, at + 300_000);
		}
	}
	return {
		bytes: chunks(),
		size: bytes.length,
		sha256: createHash("sha256").update(bytes).digest("hex"),
	};
}

/** One ustar member: the tar archives that a hostile checkout could carry, written byte by byte. */
function tarMember(
	name: string,
	options: { body?: string; size?: number; type?: string; link?: string } = {},
): Buffer {
	const body = Buffer.from(options.body ?? "");
	const header = Buffer.alloc(512);
	header.write(name, 0, 100);
	header.write(options.type === "5" ? "0000755\0" : "0000644\0", 100);
	header.write("0000000\0", 108);
	header.write("0000000\0", 116);
	header.write(`${(options.size ?? body.length).toString(8).padStart(11, "0")}\0`, 124);
	header.write("00000000000\0", 136);
	header.write("        ", 148);
	header.write(options.type ?? "0", 156);
	header.write(options.link ?? "", 157, 100);
	header.write("ustar\0", 257);
	header.write("00", 263);
	let sum = 0;
	for (const byte of header) {
		sum += byte;
	}
	header.write(`${sum.toString(8).padStart(6, "0")}\0 `, 148);
	const data = Buffer.alloc(Math.ceil(body.length / 512) * 512);
	body.copy(data);
	return Buffer.concat([header, data]);
}

const quote = (s: string) => `'${s.replaceAll("'", String.raw`'\''`)}'`;

function tarOf(...members: Buffer[]): Buffer {
	return Buffer.concat([...members, Buffer.alloc(1024)]);
}

void test("hello names the protocol and a boot id", () => {
	assert.match(client.bootId, /^[0-9a-f-]{36}$/u);
});

for (const [name, uid, status, message] of [
	["root", 0, "", /must not run as root/u],
	[
		"capabilities",
		1000,
		"CapInh:\t0\nCapPrm:\t0\nCapEff:\t1\nCapBnd:\t0\nCapAmb:\t0\nNoNewPrivs:\t1\n",
		/no capabilities/u,
	],
	[
		"no_new_privs",
		1000,
		"CapInh:\t0\nCapPrm:\t0\nCapEff:\t0\nCapBnd:\t0\nCapAmb:\t0\nNoNewPrivs:\t0\n",
		/requires no_new_privs/u,
	],
] as const) {
	void test(
		`the startup self-check refuses ${name}`,
		{ skip: process.platform !== "linux" },
		() => {
			const preload = `import fs from 'node:fs'; import { syncBuiltinESMExports } from 'node:module';
const read = fs.readFileSync; process.getuid = () => ${uid}; process.geteuid = () => ${uid};
fs.readFileSync = (file, ...args) => String(file) === '/proc/self/status' ? ${JSON.stringify(status)} : read(file, ...args);
syncBuiltinESMExports();`;
			const address = path.join(base, `refused-${name}.sock`);
			const program = fileURLToPath(
				new URL("../../../main/resources/agent/tools-server.ts", import.meta.url),
			);
			const guarded = spawnSync(
				process.execPath,
				["--import", `data:text/javascript,${encodeURIComponent(preload)}`, program],
				{
					encoding: "utf8",
					timeout: 3000,
					env: { TOOLS_NONCE: nonce, TOOLS_SOCKET: address },
				},
			);
			assert.notEqual(guarded.status, 0);
			assert.match(guarded.stderr, message);
			assert.equal(existsSync(address), false);
		},
	);
}

function authoritySnapshot() {
	return {
		uid: process.getuid?.(),
		euid: process.geteuid?.(),
		gid: process.getgid?.(),
		groups: process.getgroups?.().toSorted((a, b) => a - b),
		status: readFileSync("/proc/self/status", "utf8")
			.split("\n")
			.filter((line) => /^(?:Cap\w+|NoNewPrivs):/u.test(line)),
		mountNamespace: statSync("/proc/self/ns/mnt").ino,
	};
}

void test(
	"the entrypoint starts under the restricted command identity",
	{ skip: !soleUser },
	async () => {
		const address = path.join(base, "entrypoint.sock");
		const program = fileURLToPath(
			new URL("../../../main/resources/agent/tools-server.ts", import.meta.url),
		);
		const child = spawn(process.execPath, [program], {
			env: { ...process.env, TOOLS_NONCE: nonce, TOOLS_SOCKET: address },
			stdio: "ignore",
		});
		let tools: ToolClient | undefined;
		try {
			for (let i = 0; i < 100 && !existsSync(address) && child.exitCode === null; i += 1) {
				await sleep(10);
			}
			assert.equal(existsSync(address), true);
			tools = await ToolClient.open(address, nonce);
			const chunks: Buffer[] = [];
			const exit = await tools.exec(
				{
					argv: [process.execPath, "-e", "process.stdout.write(String(process.getuid()))"],
					cwd: "/tmp/tools/workspace",
					timeoutMs: 1000,
				},
				(chunk) => {
					chunks.push(chunk);
				},
			);
			assert.equal(exit.code, 0);
			assert.equal(Buffer.concat(chunks).toString("utf8"), String(process.getuid?.()));
		} finally {
			tools?.close();
			if (child.exitCode === null) {
				child.kill();
				await once(child, "close");
			}
		}
	},
);

void test(
	"commands inherit the server UID, groups, capabilities and mounts",
	{ skip: process.platform !== "linux" },
	async () => {
		const code = `const { readFileSync, statSync } = require('node:fs'); const snapshot = ${authoritySnapshot.toString()}; console.log(JSON.stringify(snapshot()));`;
		const chunks: Buffer[] = [];
		const exit = await client.exec(
			{ argv: [process.execPath, "-e", code], cwd: workspace, timeoutMs: 1000 },
			(chunk) => {
				chunks.push(chunk);
			},
		);
		assert.equal(exit.code, 0);
		assert.equal(
			Buffer.concat(chunks).toString("utf8").trim(),
			JSON.stringify(authoritySnapshot()),
		);
	},
);

void test("reset provides a fresh writable workspace before trash deletion finishes", async (t) => {
	const marker = path.join(workspace, "old-workspace");
	writeFileSync(marker, "old");
	const reached = Promise.withResolvers<undefined>();
	const release = Promise.withResolvers<undefined>();
	const { rm } = fs;
	let held = false;
	const rmMock = t.mock.method(fs, "rm", async (...args: Parameters<typeof fs.rm>) => {
		if (String(args[0]).includes(".tools-trash-") && !held) {
			held = true;
			reached.resolve(undefined);
			await release.promise;
		}
		return rm(...args);
	});
	syncBuiltinESMExports();
	try {
		await client.reset();
		assert.equal(existsSync(marker), false);
		await reached.promise;
		const written = await run("printf fresh > writable; cat writable");
		assert.equal(written.output, "fresh");
		assert.equal(held, true);
		await client.reset();
	} finally {
		release.resolve(undefined);
		rmMock.mock.restore();
		syncBuiltinESMExports();
	}
	await awaitTrashRemoval();
});

void test("startup retries trash left by an earlier tools server", async () => {
	const home = mkdtempSync(path.join(tmpdir(), "tools-restart-"));
	const mutable = path.join(home, "workspace");
	const oldTrash = path.join(home, ".tools-trash-old");
	mkdirSync(path.join(mutable, ".scratch"), { recursive: true });
	mkdirSync(path.join(mutable, "repos"));
	mkdirSync(oldTrash);
	writeFileSync(path.join(oldTrash, "kept"), "old");
	const restarted = startToolsServer({
		socketPath: path.join(home, "tools.sock"),
		nonce,
		workspace: mutable,
		roots: [mutable],
		scratch: path.join(mutable, ".scratch"),
		putRoots: [path.join(mutable, "repos")],
		soleUser: false,
	});
	try {
		await once(restarted, "listening");
		for (let attempt = 0; existsSync(oldTrash) && attempt < 100; attempt += 1) {
			await sleep(10);
		}
		assert.equal(existsSync(oldTrash), false);
	} finally {
		const closed = once(restarted, "close");
		restarted.close();
		await closed;
		rmSync(home, { recursive: true, force: true });
	}
});

void test("resets stop at the trash bound and recover when deletion works again", async (t) => {
	await client.reset();
	await awaitTrashRemoval();
	const { chmod } = fs;
	let blocked = true;
	const chmodMock = t.mock.method(fs, "chmod", async (...args: Parameters<typeof fs.chmod>) => {
		if (blocked && String(args[0]).includes(".tools-trash-")) {
			throw Object.assign(new Error("The trash cannot be reclaimed."), { code: "EPERM" });
		}
		await chmod(...args);
	});
	syncBuiltinESMExports();
	try {
		for (let reset = 0; reset < 4; reset += 1) {
			writeFileSync(path.join(workspace, "kept"), "old");
			await client.reset();
		}
		assert.equal(trashDirectories().length, 4);
		await assert.rejects(client.reset(), refusal("storage"));
		assert.equal(trashDirectories().length, 4);
		blocked = false;
		let recovered = false;
		for (let attempt = 0; !recovered && attempt < 100; attempt += 1) {
			try {
				await client.reset();
				recovered = true;
			} catch (error) {
				if (!refusal("storage")(error)) {
					throw error;
				}
				await sleep(10);
			}
		}
		assert.equal(recovered, true);
		const written = await run("printf recovered > writable; cat writable");
		assert.equal(written.output, "recovered");
	} finally {
		blocked = false;
		chmodMock.mock.restore();
		syncBuiltinESMExports();
	}
});

void test("parallel failed uploads reserve cleanup space before creating their directories", async (t) => {
	await client.reset();
	await awaitTrashRemoval();
	const { chmod } = fs;
	const chmodMock = t.mock.method(fs, "chmod", async (...args: Parameters<typeof fs.chmod>) => {
		if (String(args[0]).includes(".tools-trash-")) {
			throw Object.assign(new Error("The trash cannot be reclaimed."), { code: "EPERM" });
		}
		await chmod(...args);
	});
	syncBuiltinESMExports();
	try {
		const destinations = Array.from({ length: 5 }, (_unused, index) =>
			path.join(workspace, "repos", `failed-${index}`),
		);
		const results = await Promise.allSettled(
			destinations.map(async (destination) =>
				client.put(destination, archiveOf(Buffer.from("not a tar archive"))),
			),
		);
		const refusals = results.map((result) => {
			assert.ok(result.status === "rejected" && result.reason instanceof ToolProtocolError);
			return result.reason.code;
		});
		assert.equal(refusals.filter((code) => code === "storage").length, 1);
		assert.equal(refusals.filter((code) => code === "put").length, 4);
		assert.ok(destinations.every((destination) => !existsSync(destination)));
		assert.equal(trashDirectories().length, 4);
	} finally {
		chmodMock.mock.restore();
		syncBuiltinESMExports();
		try {
			await client.reset();
		} catch (error) {
			assert.ok(refusal("storage")(error));
		}
		await awaitTrashRemoval();
	}
});

void test(
	"a sole-user reset ends a detached process that cleared its marker",
	{ skip: !soleUser },
	async () => {
		const pidFile = path.join(scratch, "unmarked.pid");
		const escaped = await run(
			`setsid env -i /bin/sh -c 'echo $$ > ${pidFile}; exec /bin/sleep 60' </dev/null >/dev/null 2>&1 & while [ ! -s ${pidFile} ]; do sleep 0.01; done; cat ${pidFile}`,
		);
		const pid = Number(escaped.output.trim());
		assert.ok(Number.isInteger(pid) && pid > 1);
		try {
			assert.equal(alive(pid), true);
			await client.reset();
			for (let i = 0; alive(pid) && i < 100; i += 1) {
				await sleep(10);
			}
			assert.equal(alive(pid), false);
		} finally {
			if (alive(pid)) {
				process.kill(pid, "SIGKILL");
			}
		}
	},
);

void test(
	"trash cleanup cannot change an outside target owned by another UID",
	{
		skip: process.platform !== "linux" || process.env.TOOLS_TEST_FOREIGN_DIR === undefined,
		timeout: 5000,
	},
	async (t) => {
		const target = process.env.TOOLS_TEST_FOREIGN_DIR;
		assert.ok(target !== undefined);
		checkAuthority();
		const original = statSync(target);
		assert.notEqual(original.uid, process.getuid?.());
		const body = readFileSync(path.join(target, "kept"), "utf8");
		let current = path.join(workspace, "foreign");
		await fs.rename(target, current);
		const checked = Promise.withResolvers<undefined>();
		const { chmod } = fs;
		let moved = false;
		const chmodMock = t.mock.method(fs, "chmod", async (...args: Parameters<typeof fs.chmod>) => {
			const [file, mode] = args;
			if (String(file).endsWith("/foreign") && mode === 0o700 && !moved) {
				moved = true;
				await fs.rename(file, target);
				current = target;
				await fs.symlink(target, file);
				try {
					await chmod(file, mode);
				} finally {
					checked.resolve(undefined);
				}
				return;
			}
			await chmod(file, mode);
		});
		syncBuiltinESMExports();
		try {
			await client.reset();
			await checked.promise;
			assert.equal(statSync(target).mode, original.mode);
			assert.equal(readFileSync(path.join(target, "kept"), "utf8"), body);
			const denied = await run(`printf changed > ${quote(path.join(target, "kept"))}`);
			assert.equal(denied.exit.code, 1);
		} finally {
			chmodMock.mock.restore();
			syncBuiltinESMExports();
			if (current !== target && existsSync(current)) {
				await fs.rename(current, target);
			}
		}
	},
);

void test("an exec streams its output and ends with its exit code", async () => {
	const { exit, output } = await run("echo hello; exit 3");
	assert.equal(output, "hello\n");
	assert.equal(exit.code, 3);
	assert.equal(exit.timedOut, false);
	assert.equal(exit.outputLost, false);
	assert.equal(readFileSync(exit.fullOutputPath, "utf8"), "hello\n");
});

void test("invalid output limits use the server default without closing parallel calls", async () => {
	for (const maxOutputBytes of [
		1000.5,
		Number.NaN,
		Number.POSITIVE_INFINITY,
		Number.MAX_SAFE_INTEGER + 1,
	]) {
		const tools = await ToolClient.open(socketPath, nonce);
		let bytes = 0;
		try {
			const [exit, page] = await Promise.all([
				tools.exec(
					{ command: "head -c 4096 /dev/zero", cwd: workspace, timeoutMs: 1000, maxOutputBytes },
					(chunk) => {
						bytes += chunk.length;
					},
				),
				tools.read(path.join(workspace, "notes.txt"), 1, 1),
			]);
			assert.equal(exit.code, 0);
			assert.equal(exit.truncated, false);
			assert.equal(bytes, 4096);
			assert.equal(page.text, "one");
		} finally {
			tools.close();
		}
	}
});

void test("unencodable requests leave their slots free for a later valid request", async () => {
	const tools = await ToolClient.open(socketPath, nonce);
	const refusals: string[] = [];
	try {
		for (let call = 0; call < LIMITS.inFlight; call += 1) {
			await assert.rejects(
				tools.read(`/${"x".repeat(LIMITS.headerBytes)}`, 1, 1),
				(error: unknown) => {
					assert.ok(error instanceof ToolProtocolError);
					refusals.push(error.code);
					return true;
				},
			);
		}
		const page = await tools.read(path.join(workspace, "notes.txt"), 1, 1);
		assert.equal(page.text, "one");
		assert.deepEqual(
			refusals,
			Array.from({ length: LIMITS.inFlight }, () => "request"),
		);
	} finally {
		tools.close();
	}
});

void test("an argv runs one program with no shell", async () => {
	const chunks: Buffer[] = [];
	const exit = await client.exec(
		{ argv: ["echo", "$HOME;", "no", "expansion"], cwd: workspace, timeoutMs: 10_000 },
		(chunk) => {
			chunks.push(chunk);
		},
	);
	assert.equal(exit.code, 0);
	assert.equal(Buffer.concat(chunks).toString("utf8"), "$HOME; no expansion\n");
});

void test("a program sees only the allowlisted environment, never the tools side's own", async () => {
	const { output } = await run('echo "[$HEPHAESTUS_SECRET]"');
	assert.equal(output, "[]\n");
});

void test("a working directory or a read outside the roots is refused, also through a symlink inside them", async () => {
	await assert.rejects(
		client.exec({ command: "true", cwd: "/etc", timeoutMs: 1000 }, () => undefined),
		refusal("path"),
	);
	await assert.rejects(
		client.exec(
			{ command: "true", cwd: path.join(workspace, "escape"), timeoutMs: 1000 },
			() => undefined,
		),
		refusal("path"),
	);
	await assert.rejects(
		client.read(path.join(workspace, "escape", "hosts"), 1, 10),
		refusal("path"),
	);
	await assert.rejects(
		client.read(path.join(workspace, "..", "tools.sock"), 1, 10),
		refusal("path"),
	);
});

void test("output past the stream bound goes only to the spill file", async () => {
	const { exit, output } = await run("printf 0123456789abcdef", { maxOutputBytes: 10 });
	assert.equal(output, "0123456789");
	assert.equal(exit.truncated, true);
	assert.equal(exit.outputLost, false);
	assert.equal(readFileSync(exit.fullOutputPath, "utf8"), "0123456789abcdef");
});

void test("a call whose spill file cannot be written still runs, and says that it has none", async () => {
	const spillDir = path.join(scratch, ".tool-output");
	rmSync(spillDir, { recursive: true, force: true });
	writeFileSync(spillDir, "a file where the spill directory goes");
	try {
		const { exit, output } = await run("echo still here");
		assert.equal(output, "still here\n");
		assert.equal(exit.code, 0);
		assert.equal(exit.fullOutputPath, "");
		const cut = await run("printf 0123456789", { maxOutputBytes: 4 });
		assert.equal(cut.exit.truncated, true);
		assert.equal(cut.exit.outputLost, true);
	} finally {
		rmSync(spillDir, { force: true });
	}
});

void test("a NUL byte in a command is refused, and the tools side keeps serving", async () => {
	await assert.rejects(run("echo a\u0000b"), refusal("request"));
	await assert.rejects(
		client.exec({ argv: ["echo", "a\u0000b"], cwd: workspace, timeoutMs: 1000 }, () => undefined),
		refusal("request"),
	);
	const next = await run("echo alive");
	assert.equal(next.output, "alive\n");
});

void test("a program that cannot start is refused alone, and a parallel call goes on", async () => {
	const running = run("sleep 0.5; echo done");
	await assert.rejects(
		client.exec(
			{ argv: ["no-such-program-xyz"], cwd: workspace, timeoutMs: 1000 },
			() => undefined,
		),
		refusal("spawn"),
	);
	const { exit, output } = await running;
	assert.equal(exit.code, 0);
	assert.equal(output, "done\n");
});

void test("a call past its time is killed with its whole process group", async () => {
	const pidFile = path.join(scratch, "background.pid");
	const { exit } = await run(`sleep 30 & echo $! > ${pidFile}; sleep 30`, { timeoutMs: 500 });
	assert.equal(exit.timedOut, true);
	assert.equal(exit.signal, "SIGKILL");
	const background = Number(readFileSync(pidFile, "utf8"));
	await sleep(100);
	assert.equal(alive(background), false);
});

// A process that leaves its call's group with setsid is found by its call's marker, which only /proc shows.
void test(
	"a call leaves no process behind, also one that left its group with setsid",
	{ skip: process.platform !== "linux" },
	async () => {
		const pidFile = path.join(scratch, "detached.pid");
		const { exit } = await run(`setsid sh -c 'echo $$ > ${pidFile}; exec sleep 30' & sleep 0.3`);
		assert.equal(exit.code, 0);
		assert.equal(exit.timedOut, false, "the call ended when its program did, not at its time-out");
		const detached = Number(readFileSync(pidFile, "utf8"));
		await sleep(100);
		assert.equal(alive(detached), false);
	},
);

void test("an aborted call ends with its process, and a parallel call goes on", async () => {
	const pidFile = path.join(scratch, "cancelled.pid");
	const controller = new AbortController();
	const cancelled = run(`echo $$ > ${pidFile}; exec sleep 30`, {}, controller.signal);
	const parallel = run("sleep 0.5; echo done");
	await sleep(150);
	controller.abort();
	const { exit } = await cancelled;
	assert.equal(exit.signal, "SIGKILL");
	assert.equal(alive(Number(readFileSync(pidFile, "utf8"))), false);
	const other = await parallel;
	assert.equal(other.exit.code, 0);
	assert.equal(other.output, "done\n");
});

void test("a throwing output handler cancels its own call only", async () => {
	const failure = new Error("the loop's handler failed");
	await assert.rejects(
		client.exec({ command: "echo a; sleep 30", cwd: workspace, timeoutMs: 10_000 }, () => {
			throw failure;
		}),
		(error) => error === failure,
	);
	const next = await run("echo next");
	assert.equal(next.output, "next\n");
});

void test("calls on one connection run at once", async () => {
	const a = path.join(scratch, "a");
	const b = path.join(scratch, "b");
	// Each call waits for the other's file: both end only if both run at the same time.
	const [first, second] = await Promise.all([
		run(`touch ${a}; for i in $(seq 100); do [ -f ${b} ] && exit 0; sleep 0.05; done; exit 1`),
		run(`touch ${b}; for i in $(seq 100); do [ -f ${a} ] && exit 0; sleep 0.05; done; exit 1`),
	]);
	assert.equal(first.exit.code, 0);
	assert.equal(second.exit.code, 0);
});

void test("the loop refuses a call past the in-flight bound as busy", async () => {
	const calls = Array.from({ length: LIMITS.inFlight + 1 }, async () => run("sleep 0.3"));
	const outcomes = await Promise.allSettled(calls);
	const refused = outcomes.filter(
		(outcome): outcome is PromiseRejectedResult => outcome.status === "rejected",
	);
	assert.equal(refused.length, 1);
	assert.ok(refusal("busy")(refused[0]?.reason));
});

void test("a put refused as busy sends no archive, and the calls in flight go on", async () => {
	const calls = Array.from({ length: LIMITS.inFlight }, async () => run("sleep 0.5"));
	await assert.rejects(
		client.put(path.join(workspace, "repos", "busy"), archiveOf(Buffer.alloc(4 * 1024 * 1024))),
		refusal("busy"),
	);
	for (const { exit } of await Promise.all(calls)) {
		assert.equal(exit.code, 0);
	}
});

void test("a read pages the lines of a file", async () => {
	const page = await client.read(path.join(workspace, "notes.txt"), 2, 2);
	assert.deepEqual(page, {
		text: "two\nthree",
		offset: 2,
		totalLines: 4,
		truncated: false,
		nextOffset: 4,
	});
});

void test("a page past the body bound says so and names where the next page starts", async () => {
	const file = path.join(workspace, "long-line.txt");
	writeFileSync(file, `a\n${"é".repeat(LIMITS.bodyBytes)}\nc\n`);
	const first = await client.read(file, 1, 3);
	assert.equal(first.text, "a");
	assert.equal(first.truncated, true);
	assert.equal(first.nextOffset, 2);
	const long = await client.read(file, 2, 1);
	assert.equal(long.truncated, true);
	assert.equal(long.nextOffset, 3);
	assert.ok(Buffer.byteLength(long.text) <= LIMITS.bodyBytes);
	assert.match(long.text, /^é+$/u, "the cut falls at a character boundary");
	const last = await client.read(file, 3, 10);
	assert.deepEqual(
		{ text: last.text, truncated: last.truncated, totalLines: last.totalLines },
		{ text: "c", truncated: false, totalLines: 3 },
	);
});

void test("a read keeps a UTF-8 character split at the page's byte bound whole", async () => {
	const file = path.join(workspace, "split-character.txt");
	const prefix = "a".repeat(LIMITS.bodyBytes - 1);
	writeFileSync(file, `${prefix}€\nnext\n`);
	const page = await client.read(file, 1, 1);
	assert.ok(Buffer.byteLength(page.text) <= LIMITS.bodyBytes);
	assert.equal(page.text, prefix);
	assert.equal(page.truncated, true);
	assert.equal(page.nextOffset, 2);
});

void test("failed archive sources release their slots and stored bytes", async () => {
	const tools = await ToolClient.open(socketPath, nonce);
	const failure = new Error("archive source failed");
	async function* source(partial: boolean) {
		if (partial) {
			yield Buffer.alloc(1024);
		}
		throw failure;
	}
	try {
		for (let i = 0; i < LIMITS.inFlight; i += 1) {
			await assert.rejects(
				tools.put(path.join(workspace, "repos", `failed-source-${i}`), {
					bytes: source(i % 2 === 1),
					size: 4096,
					sha256: "0".repeat(64),
				}),
				(error) => error === failure,
			);
		}
		const reply = await tools.exec(
			{ command: "true", cwd: workspace, timeoutMs: 1000 },
			() => undefined,
		);
		assert.equal(reply.code, 0);
		assert.deepEqual(readdirSync(path.join(scratch, ".puts")), []);
	} finally {
		tools.close();
	}
});

void test("a source that fails on return never extracts its complete archive", async () => {
	const bytes = tarOf(tarMember("never", { body: "not admitted" }));
	const failure = new Error("source did not finish");
	const archive = {
		...archiveOf(bytes),
		bytes: {
			[Symbol.asyncIterator]() {
				return {
					async next() {
						return { done: false, value: bytes };
					},
					async return() {
						throw failure;
					},
				};
			},
		},
	} satisfies PutArchive;
	const dir = path.join(workspace, "repos", "failed-return");
	await assert.rejects(client.put(dir, archive), (error) => error === failure);
	assert.equal(existsSync(dir), false);
	assert.deepEqual(readdirSync(path.join(scratch, ".puts")), []);
});

void test("a reset cleans a partial upload before accepting another call", async () => {
	const streaming = Promise.withResolvers<undefined>();
	const release = Promise.withResolvers<undefined>();
	async function* source() {
		yield Buffer.alloc(1024);
		await client.exec({ command: "true", cwd: workspace, timeoutMs: 1000 }, () => undefined);
		streaming.resolve(undefined);
		await release.promise;
		yield Buffer.alloc(1024);
	}
	const upload = client.put(path.join(workspace, "repos", "reset-upload"), {
		bytes: source(),
		size: 4096,
		sha256: "0".repeat(64),
	});
	const result = Promise.allSettled([upload]);
	try {
		await streaming.promise;
		await client.reset();
		assert.deepEqual(readdirSync(scratch), []);
		const next = await run("echo reset");
		assert.equal(next.output, "reset\n");
	} finally {
		release.resolve(undefined);
	}
	const [outcome] = await result;
	assert.equal(outcome.status, "rejected");
	assert.ok(refusal("reset")(outcome.reason));
});

async function resetReadOnlyTree(
	t: TestContext,
	phase: string,
	resetClient = client,
): Promise<void> {
	const dir = path.join(realpathSync(workspace), "repos", `reset-readonly-${phase}`);
	const archive = archiveOf(
		tarOf(tarMember("nested/", { type: "5" }), tarMember("nested/file", { body: "kept" })),
	);
	const reached = Promise.withResolvers<undefined>();
	const aborted = Promise.withResolvers<undefined>();
	const release = Promise.withResolvers<undefined>();
	let held = false;
	const { chmod } = fs;
	const gate: typeof fs.chmod = async (file, mode) => {
		await chmod(file, mode);
		if (
			phase === "permissions" &&
			String(file) === path.join(dir, "nested") &&
			mode === 0o555 &&
			!held
		) {
			held = true;
			reached.resolve(undefined);
			await release.promise;
		}
	};
	const chmodMock = t.mock.method(fs, "chmod", gate);
	const { rm } = fs;
	const rmMock = t.mock.method(fs, "rm", async (...args: Parameters<typeof fs.rm>) => {
		if (phase === "archive cleanup" && String(args[0]).endsWith(".tar") && !held) {
			held = true;
			reached.resolve(undefined);
			await release.promise;
		}
		return rm(...args);
	});
	const abort: unknown = Object.getOwnPropertyDescriptor(AbortController.prototype, "abort")?.value;
	assert.ok(typeof abort === "function");
	const abortMock = t.mock.method(
		AbortController.prototype,
		"abort",
		function (this: AbortController, reason?: unknown) {
			Reflect.apply(abort, this, [reason]);
			if (held) {
				aborted.resolve(undefined);
			}
		},
	);
	syncBuiltinESMExports();
	try {
		const upload = Promise.allSettled([client.put(dir, archive)]);
		await reached.promise;
		const reset = resetClient.reset();
		await aborted.promise;
		release.resolve(undefined);
		await reset;
		const [outcome] = await upload;
		assert.equal(outcome.status, "rejected");
		assert.ok(refusal("reset")(outcome.reason));
		assert.equal(existsSync(dir), false);
		await client.put(dir, archiveOf(tarOf(tarMember("retried", { body: "success" }))));
		assert.equal(readFileSync(path.join(dir, "retried"), "utf8"), "success");
	} finally {
		release.resolve(undefined);
		chmodMock.mock.restore();
		rmMock.mock.restore();
		abortMock.mock.restore();
		syncBuiltinESMExports();
		if (existsSync(dir)) {
			execFileSync("chmod", ["-R", "u+w", dir]);
			rmSync(dir, { recursive: true, force: true });
		}
	}
}

for (const phase of ["permissions", "archive cleanup"]) {
	void test(`reset during ${phase} removes the read-only tree and allows retry`, async (t) =>
		resetReadOnlyTree(t, phase));
}

void test("reset stops an upload on another connection before replacing the workspace", async (t) => {
	const other = await ToolClient.open(socketPath, nonce);
	try {
		await resetReadOnlyTree(t, "permissions", other);
	} finally {
		other.close();
	}
});

void test("reset fences an upload still preparing and rejects overlapping resets", async (t) => {
	const resetting = await ToolClient.open(socketPath, nonce);
	const probing = await ToolClient.open(socketPath, nonce);
	const reached = Promise.withResolvers<undefined>();
	const release = Promise.withResolvers<undefined>();
	const { mkdir } = fs;
	let held = false;
	const mkdirMock = t.mock.method(fs, "mkdir", async (...args: Parameters<typeof fs.mkdir>) => {
		const result = await mkdir(...args);
		if (String(args[0]) === path.join(scratch, ".puts") && !held) {
			held = true;
			reached.resolve(undefined);
			await release.promise;
		}
		return result;
	});
	syncBuiltinESMExports();
	const directory = path.join(workspace, "repos", "preparing-reset");
	const upload = Promise.allSettled([
		client.put(directory, archiveOf(tarOf(tarMember("old", { body: "old" })))),
	]);
	try {
		await reached.promise;
		const reset = resetting.reset();
		let busy = false;
		for (let i = 0; i < 100 && !busy; i += 1) {
			try {
				await probing.exec({ command: "true", cwd: workspace, timeoutMs: 1000 }, () => undefined);
			} catch (error) {
				assert.ok(refusal("busy")(error));
				busy = true;
			}
		}
		assert.equal(busy, true);
		await assert.rejects(probing.reset(), refusal("busy"));
		release.resolve(undefined);
		await reset;
		const [outcome] = await upload;
		assert.equal(outcome.status, "rejected");
		assert.ok(refusal("reset")(outcome.reason));
		assert.equal(existsSync(directory), false);
		await client.put(directory, archiveOf(tarOf(tarMember("fresh", { body: "fresh" }))));
		assert.equal(readFileSync(path.join(directory, "fresh"), "utf8"), "fresh");
	} finally {
		release.resolve(undefined);
		mkdirMock.mock.restore();
		syncBuiltinESMExports();
		resetting.close();
		probing.close();
		await upload;
	}
});

void test("reset waits for the extraction child to close before removing its archive", async (t) => {
	const root = realpathSync(mkdtempSync(path.join(tmpdir(), "tools-extract-")));
	const marker = path.join(root, "child.pid");
	const previousPath = process.env.PATH;
	writeFileSync(
		path.join(root, "tar"),
		`#!/bin/sh\necho $$ > ${quote(marker)}\nexec /bin/sleep 60\n`,
		{ mode: 0o755 },
	);
	process.env.PATH = `${root}${path.delimiter}${previousPath ?? ""}`;
	const dir = path.join(workspace, "repos", "reset-extract");
	const upload = Promise.allSettled([client.put(dir, archiveOf(Buffer.alloc(1024)))]);
	let pid: number | undefined;
	const aliveAtRemoval: boolean[] = [];
	const remove = fs.rm;
	const removal: typeof fs.rm = async (file, options) => {
		if (String(file).endsWith(".tar") && pid !== undefined) {
			aliveAtRemoval.push(alive(pid));
		}
		await remove(file, options);
	};
	const rmMock = t.mock.method(fs, "rm", removal);
	syncBuiltinESMExports();
	try {
		for (let i = 0; !existsSync(marker) && i < 200; i += 1) {
			await sleep(10);
		}
		assert.ok(existsSync(marker));
		pid = Number(readFileSync(marker, "utf8"));
		assert.equal(alive(pid), true);
		await client.reset();
		assert.deepEqual(aliveAtRemoval, [false]);
		assert.equal(alive(pid), false);
		assert.equal(existsSync(dir), false);
		const [outcome] = await upload;
		assert.equal(outcome.status, "rejected");
		assert.ok(refusal("reset")(outcome.reason));
	} finally {
		rmMock.mock.restore();
		syncBuiltinESMExports();
		if (previousPath === undefined) {
			delete process.env.PATH;
		} else {
			process.env.PATH = previousPath;
		}
		if (pid !== undefined && alive(pid)) {
			process.kill(pid, "SIGKILL");
		}
		await upload;
		rmSync(root, { recursive: true, force: true });
	}
});

async function* refusedPutSource() {
	yield Buffer.alloc(1024);
	// The exec reply proves that the server and client handled the earlier refusal before the next chunk.
	await client.exec({ command: "true", cwd: workspace, timeoutMs: 1000 }, () => undefined);
	yield Buffer.alloc(1024);
}

void test("refused puts acknowledge their end before another upload uses their slot", async () => {
	for (let i = 0; i < LIMITS.inFlight * 3; i += 1) {
		await assert.rejects(
			client.put(outside, { bytes: refusedPutSource(), size: 4096, sha256: "0".repeat(64) }),
			refusal("request"),
		);
	}
	const next = await run("echo still open");
	assert.equal(next.output, "still open\n");
});

void test(
	"concurrent large puts keep bounded memory while another extraction waits",
	{ timeout: 30_000 },
	async (t) => {
		const root = realpathSync(mkdtempSync(path.join(tmpdir(), "tools-stream-")));
		const mutable = path.join(root, "workspace");
		const repos = path.join(mutable, "repos");
		const incoming = path.join(mutable, ".scratch");
		const bin = path.join(root, "bin");
		execFileSync("mkdir", ["-p", repos, incoming, bin]);
		const held = path.join(repos, "held");
		const started = path.join(root, "extracting");
		const release = path.join(root, "release");
		writeFileSync(
			path.join(bin, "tar"),
			`#!/bin/sh\nfor arg do\nif [ "$arg" = ${quote(held)} ]; then\ntouch ${quote(started)}\nwhile [ ! -f ${quote(release)} ]; do sleep 0.02; done\nfi\ndone\nexec /usr/bin/tar "$@"\n`,
			{ mode: 0o755 },
		);
		const address = path.join(root, "tools.sock");
		const module = new URL("../../../main/resources/agent/tools-server.ts", import.meta.url).href;
		const program = `import { startToolsServer } from ${JSON.stringify(module)};
const server = startToolsServer(${JSON.stringify({ socketPath: address, nonce, workspace: mutable, roots: [repos, incoming], scratch: incoming, putRoots: [repos], soleUser: false })});
let peak = 0;
const sample = () => { global.gc(); peak = Math.max(peak, process.memoryUsage().external); };
const timer = setInterval(sample, 20);
server.on('listening', () => { sample(); process.send({ready: true, peak}); });
process.on('message', () => { sample(); process.send({peak}); });
`;
		const child = spawn(process.execPath, ["--expose-gc", "--input-type=module", "-e", program], {
			env: { ...process.env, PATH: `${bin}${path.delimiter}${process.env.PATH ?? ""}` },
			stdio: ["ignore", "inherit", "inherit", "ipc"],
		});
		let tools: ToolClient | undefined;
		let uploads: Promise<PromiseSettledResult<void>[]> | undefined;
		try {
			const readyMessages: unknown[] = await once(child, "message");
			const ready = readyMessages[0];
			assert.ok(
				typeof ready === "object" &&
					ready !== null &&
					"peak" in ready &&
					typeof ready.peak === "number",
			);
			tools = await ToolClient.open(address, nonce);
			const first = tools.put(held, archiveOf(tarOf(tarMember("small", { body: "x" }))));
			const firstDone = Promise.allSettled([first]);
			for (let i = 0; !existsSync(started) && i < 200; i += 1) {
				await sleep(10);
			}
			assert.ok(existsSync(started), "the first extraction reached its gate");
			const size = 128 * 1024 * 1024;
			const chunk = Buffer.alloc(64 * 1024);
			const header = tarMember("large", { size });
			const end = Buffer.alloc(1024);
			const hash = createHash("sha256").update(header);
			for (let at = 0; at < size; at += chunk.length) {
				hash.update(chunk);
			}
			const sha256 = hash.update(end).digest("hex");
			const supplied = [Promise.withResolvers<undefined>(), Promise.withResolvers<undefined>()];
			const large = supplied.map(async (done, index) => {
				async function* source() {
					yield header;
					for (let at = 0; at < size; at += chunk.length) {
						yield chunk;
					}
					done.resolve(undefined);
					yield end;
				}
				assert.ok(tools);
				return tools.put(path.join(repos, `large-${index}`), {
					bytes: source(),
					size: header.length + size + end.length,
					sha256,
				});
			});
			uploads = Promise.allSettled([
				(async () => {
					const [outcome] = await firstDone;
					if (outcome.status === "rejected") {
						throw outcome.reason;
					}
				})(),
				...large,
			]);
			await Promise.all(supplied.map(async (done) => done.promise));
			const stats = once(child, "message");
			child.send("stats");
			const memoryMessages: unknown[] = await stats;
			const memory = memoryMessages[0];
			assert.ok(
				typeof memory === "object" &&
					memory !== null &&
					"peak" in memory &&
					typeof memory.peak === "number",
			);
			t.diagnostic(
				`peak added external memory: ${memory.peak - ready.peak} bytes for two 128 MiB uploads`,
			);
			assert.ok(
				memory.peak - ready.peak < 64 * 1024 * 1024,
				`tools buffered ${memory.peak - ready.peak} bytes during concurrent uploads`,
			);
			writeFileSync(release, "");
			const outcomes = await uploads;
			assert.ok(outcomes.every((outcome) => outcome.status === "fulfilled"));
			for (let i = 0; i < large.length; i += 1) {
				assert.equal(statSync(path.join(repos, `large-${i}`, "large")).size, size);
			}
		} finally {
			writeFileSync(release, "");
			tools?.close();
			await uploads;
			const exited = once(child, "exit");
			child.kill();
			await exited;
			execFileSync("chmod", ["-R", "u+w", root]);
			rmSync(root, { recursive: true, force: true });
		}
	},
);

void test("a put extracts a whole archive read-only, into a new directory only", async () => {
	const source = mkdtempSync(path.join(tmpdir(), "archive-"));
	writeFileSync(path.join(source, "README.md"), "pinned\n");
	writeFileSync(path.join(source, "run.sh"), "#!/bin/sh\n", { mode: 0o755 });
	const archive = execFileSync("tar", ["-c", "-C", source, "README.md", "run.sh"]);
	const dir = path.join(workspace, "repos", "acme");

	await client.put(dir, archiveOf(archive));

	assert.equal(readFileSync(path.join(dir, "README.md"), "utf8"), "pinned\n");
	assert.equal(statSync(path.join(dir, "README.md")).mode.toString(8).slice(-3), "444");
	assert.equal(statSync(path.join(dir, "run.sh")).mode.toString(8).slice(-3), "555");
	await assert.rejects(client.put(dir, archiveOf(archive)), refusal("request"));
	await assert.rejects(
		client.put(path.join(workspace, "notes"), archiveOf(archive)),
		refusal("request"),
	);
});

void test("a hostile archive writes nothing outside its directory", async () => {
	const absolute = path.join(outside, "absolute.txt");
	const cases = {
		"dot-dot": tarOf(tarMember("../dot-dot.txt", { body: "x" })),
		absolute: tarOf(tarMember(absolute, { body: "x" })),
		symlink: tarOf(
			tarMember("link", { type: "2", link: outside }),
			tarMember("link/through-symlink.txt", { body: "x" }),
		),
		hardlink: tarOf(
			tarMember("hard", { type: "1", link: path.join(outside, "kept.txt") }),
			tarMember("hard", { body: "overwritten" }),
		),
	};
	for (const [name, archive] of Object.entries(cases)) {
		const dir = path.join(workspace, "repos", `hostile-${name}`);
		await client.put(dir, archiveOf(archive)).catch((error: unknown) => {
			assert.ok(refusal("put")(error), `${name}: ${String(error)}`);
		});
	}
	assert.equal(existsSync(path.join(workspace, "repos", "dot-dot.txt")), false);
	assert.equal(existsSync(absolute), false);
	assert.equal(existsSync(path.join(outside, "through-symlink.txt")), false);
	assert.equal(readFileSync(path.join(outside, "kept.txt"), "utf8"), "kept\n");
});

void test("a reset ends every call and clears the scratch", async () => {
	writeFileSync(path.join(scratch, "left-over"), "x");
	const sleeping = run("sleep 30");
	await sleep(100);
	await client.reset();
	const ended = await sleeping;
	assert.equal(ended.exit.signal, "SIGKILL");
	assert.throws(() => statSync(path.join(scratch, "left-over")));
});

void test(
	"a reset ends a process that left its call's group with setsid",
	{ skip: process.platform !== "linux" },
	async () => {
		const pidFile = path.join(workspace, "reset-detached.pid");
		const sleeping = run(`setsid sh -c 'echo $$ > ${pidFile}; exec sleep 30' & sleep 30`);
		await sleep(300);
		const detached = Number(readFileSync(pidFile, "utf8"));
		await client.reset();
		await sleeping;
		await sleep(100);
		assert.equal(alive(detached), false);
	},
);

/**
 * Talks to the real tools side over a raw socket, as a loop that breaks the rules would. It writes all frames in one
 * chunk and collects the replies until `done` holds for them, or the tools side closed the connection.
 */
async function raw(
	frames: Buffer[],
	done: (replies: Header[]) => boolean = () => false,
): Promise<{ replies: Header[]; closed: boolean }> {
	const socket = connect(socketPath);
	await once(socket, "connect");
	const reader = frameReader();
	const replies: Header[] = [];
	const settled = Promise.withResolvers<boolean>();
	socket.on("data", (chunk: Buffer) => {
		replies.push(...reader.push(chunk).map((frame) => frame.header));
		if (done(replies)) {
			settled.resolve(false);
		}
	});
	socket.on("close", () => {
		settled.resolve(true);
	});
	socket.write(Buffer.concat(frames));
	const timer = setTimeout(() => {
		settled.resolve(false);
	}, 5000);
	const closed = await settled.promise;
	clearTimeout(timer);
	socket.destroy();
	return { replies, closed };
}

const hello = encode({ type: "hello", protocol: 1 });

void test("refused uploads without put-end cannot retain unbounded ids", async () => {
	const requests = Array.from({ length: LIMITS.inFlight + 1 }, (_unused, index) =>
		encode({
			type: "put",
			id: `unended-${index}`,
			dir: outside,
			size: 0,
			sha256: "0".repeat(64),
		}),
	);
	const { closed } = await raw([hello, ...requests]);
	assert.equal(closed, true);
});

void test("the tools side answers hello with its own nonce, never the loop's", async () => {
	const { replies } = await raw(
		[encode({ type: "hello", protocol: 1, nonce: "echo-me" })],
		(seen) => seen.length > 0,
	);
	assert.equal(replies[0]?.nonce, nonce);
	await assert.rejects(ToolClient.open(socketPath, "another-pod"), refusal("nonce"));
});

void test("the tools side refuses another protocol version by name, and the loop does too", async () => {
	const { replies, closed } = await raw([encode({ type: "hello", protocol: 2 })]);
	assert.deepEqual(
		replies.map((reply) => [reply.type, reply.error]),
		[["error", "version"]],
	);
	assert.equal(closed, true);
	const tools = hostile(() => undefined, { protocol: 2 });
	await assert.rejects(tools, refusal("version"));
});

void test("the tools side refuses an archive whose hash does not match, and extracts nothing", async () => {
	const archive = Buffer.from("not the archive that was announced");
	const dir = path.join(workspace, "repos", "forged");
	const { replies } = await raw(
		[
			hello,
			encode({ type: "put", id: "p1", dir, size: archive.length, sha256: "0".repeat(64) }),
			encode({ type: "put-data", id: "p1" }, archive),
			encode({ type: "put-end", id: "p1" }),
		],
		(seen) => seen.some((reply) => reply.id === "p1"),
	);
	assert.ok(replies.some((reply) => reply.type === "error" && reply.error === "put"));
	assert.throws(() => statSync(dir));
});

void test("the tools side counts a burst of requests in one chunk against its in-flight bound", async () => {
	const execs = Array.from({ length: 20 }, (_unused, index) =>
		encode({
			type: "exec",
			id: `burst-${index}`,
			command: "sleep 0.3",
			cwd: workspace,
			timeoutMs: 5000,
		}),
	);
	const { replies } = await raw(
		[hello, ...execs],
		(seen) => seen.filter((reply) => reply.type !== "hello").length === 20,
	);
	const busy = replies.filter((reply) => reply.type === "error" && reply.error === "busy");
	assert.equal(busy.length, 20 - LIMITS.inFlight);
	assert.equal(replies.filter((reply) => reply.type === "exit").length, LIMITS.inFlight);
});

void test("the tools side ends the connection on a request id that is already in flight", async () => {
	const pidFile = path.join(scratch, "duplicate.pid");
	const request = encode({
		type: "exec",
		id: "dup",
		command: `echo $$ >> ${pidFile}; exec sleep 30`,
		cwd: workspace,
		timeoutMs: 60_000,
	});
	const { closed } = await raw([hello, request, request]);
	assert.equal(closed, true);
	await sleep(200);
	const pids = existsSync(pidFile) ? readFileSync(pidFile, "utf8").trim().split("\n") : [];
	for (const pid of pids) {
		assert.equal(alive(Number(pid)), false, "the call ended with its connection");
	}
});

void test("a reset ends a call that arrived before it and had not started yet", async () => {
	const { replies } = await raw(
		[
			hello,
			encode({ type: "exec", id: "early", command: "sleep 30", cwd: workspace, timeoutMs: 60_000 }),
			encode({ type: "reset", id: "r1" }),
		],
		(seen) => seen.some((reply) => reply.id === "early") && seen.some((reply) => reply.id === "r1"),
	);
	const early = replies.find((reply) => reply.id === "early");
	assert.deepEqual([early?.type, early?.error], ["error", "reset"]);
});

/** A header with its length prefix and no body length of its own. */
function framed(header: Buffer): Buffer {
	const prefix = Buffer.alloc(4);
	prefix.writeUInt32BE(header.length);
	return Buffer.concat([prefix, header]);
}

void test("the tools side ends the connection on a malformed frame", async () => {
	const malformed = {
		"not JSON": framed(Buffer.from("{not json")),
		"an array": framed(Buffer.from("[1]")),
		"no type": framed(Buffer.from('{"bodyLength":0}')),
		"a negative body length": framed(Buffer.from('{"type":"ping","bodyLength":-1}')),
		"a fractional body length": framed(Buffer.from('{"type":"ping","bodyLength":1.5}')),
		"a body past its bound": framed(
			Buffer.from(`{"type":"ping","bodyLength":${LIMITS.bodyBytes + 1}}`),
		),
		"a request before hello": encode({ type: "read", id: "r", path: workspace }),
		"an id with path characters": Buffer.concat([hello, encode({ type: "read", id: "../x" })]),
	};
	for (const [name, frame] of Object.entries(malformed)) {
		const { closed } = await raw([frame]);
		assert.equal(closed, true, name);
	}
});

/** A tools side that says hello and then answers each of the loop's requests with `next`. */
async function hostile(
	next: (request: Header, socket: Socket) => void,
	helloReply?: Partial<Header>,
	onSocket: (socket: Socket) => void = () => undefined,
): Promise<ToolClient> {
	const hostilePath = path.join(base, `hostile-${Math.random().toString(36).slice(2)}.sock`);
	const fake = createServer((socket) => {
		onSocket(socket);
		// The loop hangs up on a broken tools side while it still writes.
		socket.on("error", () => {
			socket.destroy();
		});
		const reader = frameReader();
		socket.on("data", (chunk: Buffer) => {
			for (const { header } of reader.push(chunk)) {
				if (header.type === "hello") {
					socket.write(encode({ type: "hello", nonce, protocol: 1, bootId: "b", ...helloReply }));
				} else if (header.type !== "ping") {
					next(header, socket);
				}
			}
		});
	}).listen(hostilePath);
	await once(fake, "listening");
	after(() => fake.close());
	return ToolClient.open(hostilePath, nonce);
}

void test("the loop ends the connection on a frame for no request in flight", async () => {
	const tools = await hostile((_request, socket) => {
		socket.write(encode({ type: "out", id: "someone-else" }, Buffer.from("x")));
	});
	await assert.rejects(tools.read("/workspace/x", 1, 1), refusal("protocol"));
});

void test("the loop ends the connection when a request gets another request's final frame", async () => {
	const tools = await hostile((request, socket) => {
		socket.write(encode({ type: "exit", id: request.id, code: 0 }));
	});
	await assert.rejects(tools.read("/workspace/x", 1, 1), refusal("protocol"));
});

void test("the loop ends the connection on a header past its bound", async () => {
	const tools = await hostile((_request, socket) => {
		const length = Buffer.alloc(4);
		length.writeUInt32BE(LIMITS.headerBytes + 1);
		socket.write(length);
	});
	await assert.rejects(tools.read("/workspace/x", 1, 1), refusal("protocol"));
});

void test("the loop holds the tools side to the call's own output bound", async () => {
	const tools = await hostile((request, socket) => {
		socket.write(encode({ type: "out", id: request.id }, Buffer.alloc(2000)));
		socket.write(encode({ type: "exit", id: request.id, code: 0 }));
	});
	await assert.rejects(
		tools.exec(
			{ command: "x", cwd: "/workspace", timeoutMs: 1000, maxOutputBytes: 1000 },
			() => undefined,
		),
		refusal("protocol"),
	);
});

void test("a NaN output limit still enforces the default bound against a hostile peer", async () => {
	const frame = Buffer.alloc(LIMITS.bodyBytes);
	const tools = await hostile((request, socket) => {
		for (let sent = 0; sent <= LIMITS.execStreamBytes; sent += frame.length) {
			socket.write(encode({ type: "out", id: request.id }, frame));
		}
		socket.write(encode({ type: "exit", id: request.id, code: 0 }));
	});
	await assert.rejects(
		tools.exec(
			{ command: "x", cwd: "/workspace", timeoutMs: 1000, maxOutputBytes: Number.NaN },
			() => undefined,
		),
		refusal("protocol"),
	);
});

void test("the loop holds the tools side to the attempt's output bound", async () => {
	const frame = Buffer.alloc(LIMITS.bodyBytes);
	const tools = await hostile((request, socket) => {
		for (let sent = 0; sent < LIMITS.execStreamBytes; sent += frame.length) {
			socket.write(encode({ type: "out", id: request.id }, frame));
		}
		socket.write(encode({ type: "exit", id: request.id, code: 0 }));
	});
	const calls = LIMITS.attemptStreamBytes / LIMITS.execStreamBytes;
	for (let call = 0; call < calls; call += 1) {
		await tools.exec({ command: "x", cwd: "/workspace", timeoutMs: 1000 }, () => undefined);
	}
	await assert.rejects(
		tools.exec({ command: "x", cwd: "/workspace", timeoutMs: 1000 }, () => undefined),
		refusal("protocol"),
	);
});

void test("a put to a tools side that stopped reading ends when the connection does", async () => {
	const tools = await hostile(
		() => undefined,
		undefined,
		(socket) => {
			socket.on("data", () => {
				socket.pause();
			});
		},
	);
	const put = tools.put("/workspace/repos/stuck", archiveOf(Buffer.alloc(64 * 1024 * 1024)));
	await sleep(300);
	tools.close();
	await assert.rejects(put, refusal("protocol"));
});

// Last: by now the connection has been open past the silence bound, kept alive by its pings alone.
void test("a connection outlives the silence bound while the tools side answers its pings", async () => {
	await sleep(Math.max(0, openedAt + LIMITS.silenceMs + 1000 - Date.now()));
	const { output } = await run("echo still open");
	assert.equal(output, "still open\n");
});

void test("the frame reader refuses a frame past its bounds and joins a frame split across chunks", () => {
	const length = Buffer.alloc(4);
	length.writeUInt32BE(LIMITS.headerBytes + 1);
	assert.throws(() => frameReader().push(length), ToolProtocolError);
	assert.throws(
		() => encode({ type: "out" }, Buffer.alloc(LIMITS.bodyBytes + 1)),
		ToolProtocolError,
	);
	const frame = encode({ type: "out", id: "a" }, Buffer.from("body"));
	const reader = frameReader();
	assert.deepEqual(reader.push(frame.subarray(0, 7)), []);
	const [whole] = reader.push(frame.subarray(7));
	assert.equal(whole?.header.id, "a");
	assert.equal(whole.body.toString("utf8"), "body");
	const twice = Buffer.concat([frame, frame]);
	const bytes = [...twice].map((byte) => Buffer.from([byte]));
	const split = frameReader();
	assert.equal(bytes.flatMap((byte) => split.push(byte)).length, 2, "byte by byte, both frames");
});
