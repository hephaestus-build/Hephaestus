// Tool protocol v1 end to end: the loop's client against the tools program on a real Unix socket, and against a hostile
// tools side that breaks the protocol.

import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { once } from "node:events";
import { mkdtempSync, readFileSync, statSync, symlinkSync, writeFileSync } from "node:fs";
import { connect, createServer, type Server, type Socket } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import test, { after, before } from "node:test";
import { setTimeout as sleep } from "node:timers/promises";

import { ToolClient } from "../../../main/resources/agent/tool-client.ts";
import {
	encode,
	frameReader,
	type Header,
	LIMITS,
	ToolProtocolError,
} from "../../../main/resources/agent/tool-protocol.ts";
import { startToolsServer } from "../../../main/resources/agent/tools-server.ts";

const base = mkdtempSync(path.join(tmpdir(), "tools-"));
const workspace = path.join(base, "workspace");
const scratch = path.join(base, "tmp");
execFileSync("mkdir", ["-p", path.join(workspace, "repos"), scratch]);
writeFileSync(path.join(workspace, "notes.txt"), "one\ntwo\nthree\nfour\n");
symlinkSync("/etc", path.join(workspace, "escape"));
const socketPath = path.join(base, "tools.sock");

let server: Server;
let client: ToolClient;

before(async () => {
	process.env.HEPHAESTUS_SECRET = "must-not-leak";
	server = startToolsServer({
		socketPath,
		roots: [workspace, scratch],
		scratch,
		putRoots: [path.join(workspace, "repos")],
		soleUser: false,
	});
	await once(server, "listening");
	client = await ToolClient.open(socketPath);
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

void test("hello names the protocol and a boot id", () => {
	assert.match(client.bootId, /^[0-9a-f-]{36}$/u);
});

void test("an exec streams its output and ends with its exit code", async () => {
	const { exit, output } = await run("echo hello; exit 3");
	assert.equal(output, "hello\n");
	assert.equal(exit.code, 3);
	assert.equal(exit.timedOut, false);
	assert.equal(readFileSync(exit.fullOutputPath, "utf8"), "hello\n");
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
	assert.equal(readFileSync(exit.fullOutputPath, "utf8"), "0123456789abcdef");
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
	{
		skip: process.platform !== "linux",
	},
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

void test("an aborted call is cancelled on the tools side", async () => {
	const controller = new AbortController();
	setTimeout(() => {
		controller.abort();
	}, 100);
	const { exit } = await run("sleep 30", {}, controller.signal);
	assert.equal(exit.signal, "SIGKILL");
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

void test("the loop refuses a call past the in-flight bound", async () => {
	const calls = Array.from({ length: LIMITS.inFlight + 1 }, async () => run("sleep 0.3"));
	const outcomes = await Promise.allSettled(calls);
	assert.equal(outcomes.filter((outcome) => outcome.status === "rejected").length, 1);
});

void test("a read pages the lines of a file", async () => {
	const page = await client.read(path.join(workspace, "notes.txt"), 2, 2);
	assert.deepEqual(page, { text: "two\nthree", offset: 2, totalLines: 4 });
});

void test("a put extracts a whole archive read-only, into a new directory only", async () => {
	const source = mkdtempSync(path.join(tmpdir(), "archive-"));
	writeFileSync(path.join(source, "README.md"), "pinned\n");
	writeFileSync(path.join(source, "run.sh"), "#!/bin/sh\n", { mode: 0o755 });
	const archive = execFileSync("tar", ["-c", "-C", source, "README.md", "run.sh"]);
	const dir = path.join(workspace, "repos", "acme");

	await client.put(dir, archive);

	assert.equal(readFileSync(path.join(dir, "README.md"), "utf8"), "pinned\n");
	assert.equal(statSync(path.join(dir, "README.md")).mode.toString(8).slice(-3), "444");
	assert.equal(statSync(path.join(dir, "run.sh")).mode.toString(8).slice(-3), "555");
	await assert.rejects(client.put(dir, archive), refusal("request"));
	await assert.rejects(client.put(path.join(workspace, "notes"), archive), refusal("request"));
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

/** Talks to the real tools side over a raw socket, as a loop that breaks the rules would. */
async function raw(frames: Header[], bodies: Buffer[] = []): Promise<Header[]> {
	const socket = connect(socketPath);
	await once(socket, "connect");
	const reader = frameReader();
	const replies: Header[] = [];
	socket.on("data", (chunk: Buffer) => {
		replies.push(...reader.push(chunk).map((frame) => frame.header));
	});
	for (const [index, header] of frames.entries()) {
		socket.write(encode(header, bodies[index]));
	}
	await sleep(300);
	socket.destroy();
	return replies;
}

void test("the tools side refuses an archive whose hash does not match, and extracts nothing", async () => {
	const archive = Buffer.from("not the archive that was announced");
	const dir = path.join(workspace, "repos", "forged");
	const replies = await raw(
		[
			{ type: "hello", nonce: "n", protocol: 1 },
			{ type: "put", id: "p1", dir, size: archive.length, sha256: "0".repeat(64) },
			{ type: "put-data", id: "p1" },
			{ type: "put-end", id: "p1" },
		],
		[Buffer.alloc(0), Buffer.alloc(0), archive],
	);
	assert.ok(replies.some((reply) => reply.type === "error" && reply.error === "put"));
	assert.throws(() => statSync(dir));
});

/** A tools side that says hello and then sends `next` for the loop's first request. */
async function hostile(next: (request: Header, socket: Socket) => void): Promise<ToolClient> {
	const hostilePath = path.join(base, `hostile-${Math.random().toString(36).slice(2)}.sock`);
	const fake = createServer((socket) => {
		const reader = frameReader();
		socket.on("data", (chunk: Buffer) => {
			for (const { header } of reader.push(chunk)) {
				if (header.type === "hello") {
					socket.write(encode({ type: "hello", nonce: header.nonce, protocol: 1, bootId: "b" }));
				} else if (header.type !== "ping") {
					next(header, socket);
				}
			}
		});
	}).listen(hostilePath);
	await once(fake, "listening");
	after(() => fake.close());
	return ToolClient.open(hostilePath);
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
});
