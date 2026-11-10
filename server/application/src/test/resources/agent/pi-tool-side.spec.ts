// The loop's `bash` and `grep` on the tools side: Pi's contracts, against a real tools program and a lying one.

import assert from "node:assert/strict";
import { once } from "node:events";
import { mkdirSync, mkdtempSync, readFileSync, realpathSync, writeFileSync } from "node:fs";
import { readFile } from "node:fs/promises";
import type { Server } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import test, { after, before, type TestContext } from "node:test";

import { createGrepTool } from "@earendil-works/pi-coding-agent";

import {
	globApart,
	openToolSide,
	toolBashOperations,
	toolGrepTool,
	toolLines,
} from "../../../main/resources/agent/pi-tool-side.ts";
import { ToolClient } from "../../../main/resources/agent/tool-client.ts";
import { ToolProtocolError } from "../../../main/resources/agent/tool-protocol.ts";
import { startToolsServer } from "../../../main/resources/agent/tools-server.ts";

const base = realpathSync(mkdtempSync(path.join(tmpdir(), "tool-side-")));
const workspace = path.join(base, "workspace");
const scratch = path.join(workspace, ".scratch");
const source = path.join(workspace, "source");
mkdirSync(scratch, { recursive: true });
mkdirSync(path.join(source, "nested"), { recursive: true });
writeFileSync(path.join(source, "a.ts"), "one\ntodo: first\nthree\ntodo: second\n");
writeFileSync(path.join(source, "nested/b.ts"), "alpha\nTODO: upper\n");
writeFileSync(path.join(source, "long.ts"), `todo: ${"x".repeat(900)}\n`);
writeFileSync(path.join(base, "outside.txt"), "todo: not for the model\n");
const socketPath = path.join(base, "tools.sock");
const nonce = "pod-nonce";
let hasRg = false;

let server: Server;
let client: ToolClient;

before(async () => {
	server = startToolsServer({
		address: { path: socketPath },
		nonce,
		workspace,
		roots: [workspace],
		scratch,
		putRoots: [scratch],
		soleUser: false,
	});
	await once(server, "listening");
	client = await ToolClient.open({ path: socketPath }, nonce);
	try {
		await toolLines(client, source)(["rg", "--version"], () => true);
		hasRg = true;
	} catch (error) {
		if (!(error instanceof ToolProtocolError) || error.code !== "spawn") {
			throw error;
		}
	}
});

after(() => {
	client.close();
	server.close();
});

const read = async (file: string) => readFile(file, "utf8");

/** A side that answers every `exec` with `lines`, as a hostile `rg` would. */
function lying(lines: readonly string[], code = 0): Pick<ToolClient, "exec"> {
	return {
		exec: async (_request, onOutput) => {
			onOutput(Buffer.from(`${lines.join("\n")}\n`));
			return {
				code,
				signal: null,
				timedOut: false,
				truncated: false,
				fullOutputPath: "",
				outputLost: false,
			};
		},
	};
}

const matchLine = (file: string, line: number, text: string) =>
	JSON.stringify({
		type: "match",
		data: { path: { text: file }, line_number: line, lines: { text: `${text}\n` } },
	});

type GrepInput = Parameters<ReturnType<typeof toolGrepTool>["execute"]>[1];

/** `rg` searches files in parallel, so the order of files differs between any two runs, Pi's too. */
const lines = (text: string) => text.split("\n").toSorted();

async function grep(tools: Pick<ToolClient, "exec">, input: GrepInput) {
	const result = await toolGrepTool(tools, source, read).execute("id", input);
	return result.content.map((part) => part.text).join("");
}

/** Our answer and Pi's, on the same files. */
async function parity(input: GrepInput, context: TestContext) {
	if (!hasRg) {
		context.skip("rg is not installed on the tools side");
		return;
	}
	const pi = await createGrepTool(source).execute("id", input);
	const [expected] = pi.content;
	assert.deepEqual(
		lines(await grep(client, input)),
		lines(expected?.type === "text" ? expected.text : ""),
	);
}

void test("a side that starts late is dialled until it answers", async () => {
	const late = path.join(base, "late.sock");
	const opening = openToolSide({ path: late }, nonce);
	const started = startToolsServer({
		address: { path: late },
		nonce,
		workspace,
		roots: [workspace],
		scratch,
		putRoots: [scratch],
		soleUser: false,
	});
	const tools = await opening;
	tools.close();
	started.close();
});

void test("bash runs on the tools side and gets its exit code and its output", async () => {
	const chunks: Buffer[] = [];
	const { exitCode } = await toolBashOperations(client).exec("printf hi; exit 7", source, {
		onData: (chunk) => {
			chunks.push(chunk);
		},
	});
	assert.equal(exitCode, 7);
	assert.equal(Buffer.concat(chunks).toString(), "hi");
});

void test("a command sees the sandbox's Git hardening and none of the runner's variables", async () => {
	const runner = { LLM_PROXY_TOKEN: "must-not-leak", NODE_OPTIONS: "--max-old-space-size=64" };
	const sandbox = {
		GIT_CONFIG_COUNT: "1",
		GIT_CONFIG_KEY_0: "core.fsmonitor",
		GIT_CONFIG_VALUE_0: "false",
	};
	Object.assign(process.env, runner, sandbox);
	const chunks: Buffer[] = [];
	try {
		await toolBashOperations(client).exec("env", source, {
			onData: (chunk) => {
				chunks.push(chunk);
			},
		});
	} finally {
		for (const name of Object.keys({ ...runner, ...sandbox })) {
			Reflect.deleteProperty(process.env, name);
		}
	}
	const output = Buffer.concat(chunks).toString();
	assert.equal(output.includes("must-not-leak"), false);
	assert.doesNotMatch(output, /^NODE_OPTIONS=/mu);
	for (const [name, value] of Object.entries(sandbox)) {
		assert.match(output, new RegExp(`^${name}=${value}$`, "mu"));
	}
});

void test("a repository search runs on the tools side, and stops when it has enough", async () => {
	const seen: string[] = [];
	await toolLines(client, source)(["sh", "-c", String.raw`printf 'one\ntwo\nthree\n'`], (line) => {
		seen.push(line);
		return seen.length < 2;
	});
	assert.deepEqual(seen, ["one", "two"]);
});

void test("a search that finds nothing is no failure, and one that breaks is", async () => {
	const none = toolLines(client, source)(["sh", "-c", "exit 1"], () => true);
	await assert.doesNotReject(none);
	await assert.rejects(
		toolLines(client, source)(["sh", "-c", "exit 2"], () => true),
		/grep exited with status 2/u,
	);
});

/** A side whose call ends with a cut stream. */
function cutting(over: { truncated?: boolean; outputLost?: boolean; timedOut?: boolean }) {
	return {
		exec: async () => ({
			code: 0,
			signal: null,
			timedOut: false,
			truncated: false,
			fullOutputPath: "",
			outputLost: false,
			...over,
		}),
	};
}

for (const over of [{ truncated: true }, { outputLost: true }]) {
	void test(`a cut stream is no "no match": ${JSON.stringify(over)}`, async () => {
		await assert.rejects(grep(cutting(over), { pattern: "x" }), /output was cut/u);
		await assert.rejects(
			toolLines(cutting(over), source)(["grep", "x"], () => true),
			/output was cut/u,
		);
	});

	void test(`bash says so when its output was cut: ${JSON.stringify(over)}`, async () => {
		const chunks: Buffer[] = [];
		await toolBashOperations(cutting(over)).exec("true", source, {
			onData: (chunk) => {
				chunks.push(chunk);
			},
		});
		assert.match(Buffer.concat(chunks).toString(), /output was cut/u);
	});
}

void test("a command that timed out fails as Pi's bash fails", async () => {
	await assert.rejects(
		toolBashOperations(cutting({ timedOut: true })).exec("sleep 9", source, {
			onData: () => undefined,
			timeout: 5,
		}),
		/^Error: timeout:5$/u,
	);
});

void test("a character that a frame splits stays whole", async () => {
	const seen: string[] = [];
	const word = Buffer.from("übung\n");
	const split: Pick<ToolClient, "exec"> = {
		exec: async (_request, onOutput) => {
			onOutput(word.subarray(0, 1));
			onOutput(word.subarray(1));
			return {
				code: 0,
				signal: null,
				timedOut: false,
				truncated: false,
				fullOutputPath: "",
				outputLost: false,
			};
		},
	};
	await toolLines(split, source)(["grep", "x"], (line) => {
		seen.push(line);
		return true;
	});
	assert.deepEqual(seen, ["übung"]);
});

void test("rg's own message reaches the model, so that it can fix its pattern", async () => {
	await assert.rejects(
		grep(lying(["regex parse error: unclosed group"], 2), { pattern: "(" }),
		/unclosed group/u,
	);
});

void test("find runs apart from the loop and finds what the walk finds", async () => {
	const found = await globApart({ pattern: "*.ts", cwd: source, ignore: [], limit: 10 });
	assert.ok(found.some((file) => file.endsWith("a.ts")));
});

void test("a side that never answers ends the wait with its failure", async () => {
	await assert.rejects(openToolSide({ path: path.join(base, "none.sock") }, nonce, 50));
});

void test("a path that the answer names outside the root is not shown or read", async () => {
	const outside = path.join(base, "outside.txt");
	const text = await grep(lying([matchLine(outside, 1, "todo")]), { pattern: "todo", context: 1 });
	assert.equal(text, "outside.txt:1: (unable to read file)");
	assert.equal(text.includes(readFileSync(outside, "utf8")), false);
});

void test("a relative path that climbs out of the root is not read", async () => {
	const text = await grep(lying([matchLine("../../outside.txt", 1, "todo")]), {
		pattern: "todo",
		context: 1,
	});
	assert.equal(text, "outside.txt:1: (unable to read file)");
});

void test("broken lines in the answer are skipped, and a failure of rg is named", async () => {
	assert.equal(
		await grep(lying(["{not json", "[]", "null"]), { pattern: "x" }),
		"No matches found",
	);
	await assert.rejects(grep(lying([], 2), { pattern: "x" }), /ripgrep exited with code 2/u);
});

void test("the answer is cut at the limit, and the notice says so", async () => {
	const answer = Array.from({ length: 5 }, (_unused, index) =>
		matchLine("a.ts", index + 1, "todo"),
	);
	const text = await grep(lying(answer), { pattern: "todo", limit: 2 });
	assert.equal(text.split("\n").filter((line) => line.startsWith("a.ts")).length, 2);
	assert.match(text, /2 matches limit reached/u);
});

for (const input of [
	{ pattern: "todo" },
	{ pattern: "todo", ignoreCase: true },
	{ pattern: "todo:", literal: true },
	{ pattern: "todo", glob: "*.ts", path: "nested" },
	{ pattern: "todo", context: 1 },
	{ pattern: "todo", limit: 1, path: "a.ts" },
	{ pattern: "nothing-here" },
	{ pattern: "todo", path: "a.ts" },
]) {
	void test(`grep answers as Pi's grep does: ${JSON.stringify(input)}`, async (context) => {
		await parity(input, context);
	});
}
