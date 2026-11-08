import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { once } from "node:events";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import type { Server } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import type { SandboxReadAnswer } from "../../../main/resources/agent/heph-host-protocol.ts";
import {
	isDocument,
	MAX_BYTES,
	MAX_LINES,
	outputTail,
	piRead,
	type ToolsWorker,
	TurnTools,
} from "../../../main/resources/agent/heph-tools.ts";
import { ToolClient } from "../../../main/resources/agent/tool-client.ts";
import { roleOptions, startToolsServer } from "../../../main/resources/agent/tools-server.ts";

const NONCE = "the-pod-nonce";

/** A tools side in the conversation role on TCP, with a workspace of its own, as one tools Pod has. */
async function serve() {
	const home = mkdtempSync(path.join(tmpdir(), "heph-tools-"));
	const folder = path.join(home, "workspace");
	mkdirSync(path.join(folder, ".scratch"), { recursive: true });
	mkdirSync(path.join(folder, "repos"));
	const server: Server = startToolsServer({
		address: { host: "127.0.0.1", port: 0 },
		nonce: NONCE,
		workspace: folder,
		roots: [folder],
		scratch: path.join(folder, ".scratch"),
		...roleOptions("conversation", folder, path.join(folder, "repos")),
		soleUser: false,
	});
	await once(server, "listening");
	const address = server.address();
	assert.ok(address !== null && typeof address !== "string");
	const { port } = address;
	return {
		folder,
		port,
		[Symbol.asyncDispose]: async () => {
			const closed = once(server, "close");
			server.close();
			await closed;
			// Puts extract read-only.
			execFileSync("chmod", ["-R", "u+w", home]);
			rmSync(home, { recursive: true, force: true, maxRetries: 20, retryDelay: 50 });
		},
	};
}

function tarOf(files: Record<string, string>): Buffer {
	const source = mkdtempSync(path.join(tmpdir(), "heph-tools-source-"));
	try {
		for (const [name, text] of Object.entries(files)) {
			mkdirSync(path.dirname(path.join(source, name)), { recursive: true });
			writeFileSync(path.join(source, name), text);
		}
		return execFileSync("tar", ["-c", "-C", source, ...Object.keys(files)]);
	} finally {
		rmSync(source, { recursive: true, force: true });
	}
}

function archive(bytes: Buffer) {
	return {
		bytes: (async function* () {
			yield bytes;
		})(),
		size: bytes.length,
		sha256: createHash("sha256").update(bytes).digest("hex"),
	};
}

/** The worker's half, in memory: it records what the host asked. */
function fakeWorker(port: number, overrides: Partial<ToolsWorker> = {}) {
	const calls: string[] = [];
	const discarded: string[] = [];
	let loads = 0;
	const worker: ToolsWorker = {
		bind: async () => {
			calls.push("bind");
			return { ok: true, id: "hephaestus-tools-1", host: "127.0.0.1", port, nonce: NONCE };
		},
		discard: async (id) => {
			discarded.push(id);
		},
		documents: async () => {
			calls.push("documents");
			return tarOf({ "context/user.json": '{ "counts": 3 }\n' });
		},
		document: async (request): Promise<SandboxReadAnswer> => {
			calls.push(`document ${request.path}`);
			return { ok: true, text: `memory of ${request.path}` };
		},
		load: async () => {
			loads += 1;
			calls.push("load");
			return {
				ok: true,
				path: "repos/github.com/acme/api",
				resolved: { ref: "main", sha: "abc", cite: "@abc" },
				note: "Loaded.",
				summary: ["cat", "README.md"],
				archive: archive(tarOf({ "README.md": `# API ${loads}\n` })),
				cancel: async () => undefined,
			};
		},
		...overrides,
	};
	return { worker, calls, discarded };
}

const { signal } = new AbortController();

void test("a document read needs no tools Pod, and every other call binds one Pod once", async () => {
	await using pod = await serve();
	const { worker, calls } = fakeWorker(pod.port);
	const tools = new TurnTools(worker, undefined, pod.folder);
	try {
		assert.deepEqual(await tools.read({ path: "INDEX.md" }, signal), {
			ok: true,
			text: "memory of INDEX.md",
		});
		assert.deepEqual(calls, ["document INDEX.md"]);

		const listed = await tools.exec(
			{ command: "cat inputs/context/user.json; id -u >/dev/null" },
			signal,
		);
		assert.ok(listed.ok);
		assert.equal(listed.exitCode, 0);
		assert.equal(listed.output, '{ "counts": 3 }\n');
		await tools.exec({ command: "true" }, signal);
		assert.deepEqual(calls, ["document INDEX.md", "bind", "documents"]);
	} finally {
		await tools.close();
	}
});

void test("a load lands in the folder, a load again replaces it, and its summary follows the note", async () => {
	await using pod = await serve();
	const { worker } = fakeWorker(pod.port);
	const tools = new TurnTools(worker, undefined, pod.folder);
	try {
		const first = await tools.load({ path: "repos/github.com/acme/api" }, signal);
		assert.deepEqual(first, {
			ok: true,
			path: "repos/github.com/acme/api",
			resolved: { ref: "main", sha: "abc", cite: "@abc" },
			note: "Loaded.\n# API 1",
		});
		const second = await tools.load({ path: "repos/github.com/acme/api@pull/1" }, signal);
		assert.ok(second.ok);
		assert.equal(second.note, "Loaded.\n# API 2");
		assert.deepEqual(await tools.read({ path: "repos/github.com/acme/api/README.md" }, signal), {
			ok: true,
			text: "# API 2\n",
		});
		const refused = await tools.read({ path: "repos/github.com/acme/api" }, signal);
		assert.ok(!refused.ok);
		assert.equal(refused.error, "IS_DIRECTORY");
		const missing = await tools.read({ path: "repos/none.md" }, signal);
		assert.ok(!missing.ok);
		assert.equal(missing.error, "NOT_FOUND");
	} finally {
		await tools.close();
	}
});

void test("a Pod whose nonce does not match is never used, and the worker removes it", async () => {
	await using pod = await serve();
	const { worker, discarded } = fakeWorker(pod.port, {
		bind: async () => ({
			ok: true,
			id: "hephaestus-tools-9",
			host: "127.0.0.1",
			port: pod.port,
			nonce: "another",
		}),
	});
	const tools = new TurnTools(
		worker,
		async (address, nonce) => ToolClient.open(address, nonce),
		pod.folder,
	);
	const answer = await tools.exec({ command: "true" }, signal);
	assert.deepEqual(answer, {
		ok: false,
		error: "UNAVAILABLE",
		detail: "The tools did not start. Try again once.",
	});
	assert.deepEqual(discarded, ["hephaestus-tools-9"]);
});

void test("a refusal of the worker is the answer, and the next call asks again", async () => {
	await using pod = await serve();
	let binds = 0;
	const { worker } = fakeWorker(pod.port, {
		bind: async () => {
			binds += 1;
			return { ok: false, error: "CONTEXT_CHANGED", detail: "The context folder changed." };
		},
	});
	const tools = new TurnTools(worker, undefined, pod.folder);
	assert.deepEqual(await tools.exec({ command: "true" }, signal), {
		ok: false,
		error: "CONTEXT_CHANGED",
		detail: "The context folder changed.",
	});
	await tools.exec({ command: "true" }, signal);
	assert.equal(binds, 2);
});

void test("a bind that threw is not kept, so the next call binds again", async () => {
	await using pod = await serve();
	let binds = 0;
	const { worker } = fakeWorker(pod.port);
	const { bind } = worker;
	worker.bind = async (aborted) => {
		binds += 1;
		if (binds === 1) {
			throw new Error("the worker is unreachable");
		}
		return bind(aborted);
	};
	const tools = new TurnTools(worker, undefined, pod.folder);
	try {
		await assert.rejects(tools.exec({ command: "true" }, signal), /unreachable/u);
		const answer = await tools.exec({ command: "true" }, signal);
		assert.ok(answer.ok);
		assert.equal(binds, 2);
	} finally {
		await tools.close();
	}
});

void test("a load the worker refuses binds no Pod, and loads of one directory at once both land", async () => {
	await using pod = await serve();
	const refusing = fakeWorker(pod.port, {
		load: async () => ({ ok: false, error: "NOT_VISIBLE", detail: "You cannot read it." }),
	});
	const none = new TurnTools(refusing.worker, undefined, pod.folder);
	assert.deepEqual(await none.load({ path: "repos/github.com/acme/api" }, signal), {
		ok: false,
		error: "NOT_VISIBLE",
		detail: "You cannot read it.",
	});
	assert.ok(!refusing.calls.includes("bind"));

	const { worker, discarded } = fakeWorker(pod.port);
	const tools = new TurnTools(worker, undefined, pod.folder);
	try {
		const both = await Promise.all([
			tools.load({ path: "repos/github.com/acme/api" }, signal),
			tools.load({ path: "repos/github.com/acme/api@pull/1" }, signal),
		]);
		assert.ok(both.every((answer) => answer.ok));
		assert.deepEqual(discarded, []);
		assert.deepEqual(await tools.read({ path: "repos/github.com/acme/api/README.md" }, signal), {
			ok: true,
			text: "# API 2\n",
		});
	} finally {
		await tools.close();
	}
});

void test("a page of an empty line keeps its newline, and an offset must be a whole number", async () => {
	await using pod = await serve();
	writeFileSync(path.join(pod.folder, "repos", "lines.txt"), "a\n\nb\n");
	const { worker } = fakeWorker(pod.port);
	const tools = new TurnTools(worker, undefined, pod.folder);
	try {
		assert.deepEqual(await tools.read({ path: "repos/lines.txt", offset: 2, limit: 1 }, signal), {
			ok: true,
			text: "\n\n[2 more lines in file. Use offset=3 to continue.]",
		});
		const fractional = await tools.read({ path: "repos/lines.txt", offset: 1.5 }, signal);
		assert.ok(!fractional.ok);
		assert.equal(fractional.error, "INVALID_ARGUMENT");
	} finally {
		await tools.close();
	}
});

void test("a Pod the worker removed for changed data answers CONTEXT_CHANGED, not a failure to retry", async () => {
	await using pod = await serve();
	let binds = 0;
	const { worker, discarded } = fakeWorker(pod.port);
	const { bind } = worker;
	worker.bind = async (aborted) => {
		binds += 1;
		return binds === 2
			? { ok: false, error: "CONTEXT_CHANGED", detail: "The context folder changed." }
			: bind(aborted);
	};
	let opened: ToolClient | undefined;
	const tools = new TurnTools(
		worker,
		async (address, nonce) => (opened = await ToolClient.open(address, nonce)),
		pod.folder,
	);
	try {
		const first = await tools.exec({ command: "true" }, signal);
		assert.ok(first.ok);
		// The worker's removal ends the connection.
		opened?.close();
		assert.deepEqual(await tools.exec({ command: "true" }, signal), {
			ok: false,
			error: "CONTEXT_CHANGED",
			detail: "The context folder changed.",
		});
		assert.deepEqual(discarded, ["hephaestus-tools-1"]);
		assert.equal(binds, 2);
	} finally {
		await tools.close();
	}
});

void test("only the turn's index, field guide and context documents are read from memory", () => {
	assert.ok(isDocument("INDEX.md"));
	assert.ok(isDocument("/tmp/tools/workspace/FIELDS.md"));
	assert.ok(isDocument("inputs/context/issue/7.json"));
	assert.ok(!isDocument("inputs/other.json"));
	assert.ok(!isDocument("repos/github.com/acme/api/INDEX.md"));
	assert.ok(!isDocument("../INDEX.md"));
});

void test("a page is Pi's own: whole, cut by a limit, past the end, or past the byte bound", () => {
	const file = Buffer.from("one\ntwo\nthree\nfour\n");
	assert.deepEqual(piRead("a.txt", undefined, undefined, 4, 3, file), {
		text: "one\ntwo\nthree\nfour\n",
	});
	assert.deepEqual(piRead("a.txt", 2, 2, 4, 3, Buffer.from("two\nthree\n")), {
		text: "two\nthree\n\n[2 more lines in file. Use offset=4 to continue.]",
	});
	assert.deepEqual(piRead("a.txt", 9, undefined, 4, 0, Buffer.alloc(0)), {
		offsetError: "Offset 9 is beyond end of file (5 lines total)",
	});
	const line = "x".repeat(1000);
	const big = Buffer.from(`${line}\n`.repeat(60)).subarray(0, MAX_BYTES + 1);
	const page = piRead("big.txt", undefined, undefined, 60, 1000, big);
	assert.ok("text" in page);
	assert.match(
		page.text,
		/\n\n\[Showing lines 1-51 of 61 \(50\.0KB limit\)\. Use offset=52 to continue\.\]$/u,
	);
	assert.deepEqual(
		piRead("wide.txt", undefined, undefined, 0, MAX_BYTES + 10, Buffer.alloc(MAX_BYTES + 1, 0x78)),
		{
			text: `[Line 1 is 50.0KB, exceeds 50.0KB limit. Use bash: sed -n '1p' wide.txt | head -c ${MAX_BYTES}]`,
		},
	);
});

void test("an output is Pi's own tail: whole, or its last lines with the spill file named", () => {
	const short = outputTail();
	short.push(Buffer.from("hello\n"));
	assert.deepEqual(short.text("/spill"), { text: "hello\n", truncated: false });

	const long = outputTail();
	for (let line = 1; line <= MAX_LINES + 5; line += 1) {
		long.push(Buffer.from(`${line}\n`));
	}
	const shown = long.text("/spill/1.log");
	assert.ok(shown.truncated);
	assert.match(shown.text, /^6\n/u);
	assert.match(
		shown.text,
		new RegExp(
			`\\n\\n\\[Showing lines 6-${MAX_LINES + 5} of ${MAX_LINES + 5}\\. Full output: /spill/1\\.log\\]$`,
			"u",
		),
	);
});
