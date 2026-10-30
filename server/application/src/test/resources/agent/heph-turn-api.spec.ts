// The host's HTTP client for a turn against the worker's wire shape. The request bodies and the context are the files
// in heph-turn-contract/, which HephTurnControllerIntegrationTest sends to and reads from the real controller.

import assert from "node:assert/strict";
import { once } from "node:events";
import { readFileSync } from "node:fs";
import { createServer } from "node:http";
import test from "node:test";

import { isRecord, type PartialMessage } from "../../../main/resources/agent/heph-host-protocol.ts";
import {
	httpTurnApi,
	isLeaseLost,
	isTooLarge,
	WorkerStatusError,
} from "../../../main/resources/agent/heph-host.ts";

function contract(name: string): unknown {
	const value: unknown = JSON.parse(
		readFileSync(new URL(`heph-turn-contract/${name}`, import.meta.url), "utf8"),
	);
	return value;
}

interface Received {
	method: string;
	path: string;
	authorization: string;
	body: unknown;
}

interface Answer {
	status: number;
	body?: unknown;
}

/** A worker that answers each request with the next of `answers` and records what it received. */
async function fakeWorker(answers: Answer[]) {
	const received: Received[] = [];
	const server = createServer((request, response) => {
		let text = "";
		request.on("data", (chunk: Buffer) => {
			text += chunk.toString("utf8");
		});
		request.on("end", () => {
			const parsed: unknown = text === "" ? undefined : JSON.parse(text);
			received.push({
				method: request.method ?? "",
				path: request.url ?? "",
				authorization: request.headers.authorization ?? "",
				body: parsed,
			});
			const answer = answers.shift() ?? { status: 204 };
			if (answer.body === undefined) {
				response.writeHead(answer.status).end();
			} else {
				response
					.writeHead(answer.status, { "content-type": "application/json" })
					.end(JSON.stringify(answer.body));
			}
		});
	});
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	const address = server.address();
	if (address === null || typeof address === "string") {
		throw new Error("no port");
	}
	return {
		received,
		// Repeats wait no time here; the delays are the host's to choose.
		api: httpTurnApi(`http://127.0.0.1:${address.port}`, "turn-1", "turn-token", [0, 0, 0]),
		close: () => server.close(),
	};
}

function recordOf(value: unknown): Record<string, unknown> {
	assert.ok(isRecord(value));
	return value;
}

function isList(value: unknown): value is unknown[] {
	return Array.isArray(value);
}

function isPartialMessages(value: unknown): value is PartialMessage[] {
	return isList(value) && value.every((message) => isRecord(message) && isList(message.content));
}

const READ_ANSWER = { ok: true, text: '{\n  "pullRequests": []\n}' };
const EXEC_ANSWER = {
	ok: true,
	exitCode: 0,
	output: "c0ffee1 Pin the image\n",
	truncated: false,
	timedOut: false,
	fullOutputPath: null,
};
const LOAD_ANSWER = {
	ok: true,
	path: "repos/github.com/acme/api",
	resolved: { ref: "pull/42", sha: "7044a8a0c0ffee0c0ffee0c0ffee0c0ffee0c0ff", cite: "@7044a8a" },
	note: "Staged pull request #42 of acme/api.",
};

void test("a tools quota wait ends when its turn is stopped", async () => {
	const received = Promise.withResolvers<undefined>();
	const server = createServer(() => received.resolve(undefined));
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	const address = server.address();
	assert.ok(address !== null && typeof address !== "string");
	const stop = new AbortController();
	const api = httpTurnApi(`http://127.0.0.1:${address.port}`, "turn-1", "turn-token");
	const waiting = api.read({ path: "INDEX.md" }, stop.signal);
	try {
		await received.promise;
		stop.abort(new Error("turn stopped"));
		await assert.rejects(waiting, /turn stopped/u);
	} finally {
		stop.abort();
		server.closeAllConnections();
		server.close();
	}
});

void test("tools wait for their turn budget instead of the shorter worker-request timeout", async (context) => {
	const worker = await fakeWorker([
		{ status: 200, body: { ok: false, error: "NOT_FOUND", detail: "No such file." } },
	]);
	context.mock.method(AbortSignal, "timeout", () =>
		AbortSignal.abort(new Error("request timeout")),
	);
	try {
		const answer = await worker.api.read({ path: "INDEX.md" }, new AbortController().signal);
		assert.deepEqual(answer, { ok: false, error: "NOT_FOUND", detail: "No such file." });
	} finally {
		worker.close();
	}
});

void test("the host sends its partial answer, searches, sandbox calls and end in the shape the worker binds", async () => {
	const worker = await fakeWorker([
		{ status: 204 },
		{ status: 200, body: { hits: [] } },
		{ status: 200, body: READ_ANSWER },
		{ status: 200, body: EXEC_ANSWER },
		{ status: 200, body: LOAD_ANSWER },
	]);
	try {
		const partial = recordOf(contract("partial.request.json"));
		const search = recordOf(contract("search.request.json"));
		const read = recordOf(contract("sandbox-read.request.json"));
		const exec = recordOf(contract("sandbox-exec.request.json"));
		const load = recordOf(contract("sandbox-load.request.json"));
		const end = recordOf(contract("end.request.json"));
		const deadline = recordOf(contract("end-deadline.request.json"));
		assert.ok(isPartialMessages(partial.messages));
		assert.ok(isList(end.entries));
		assert.ok(typeof read.path === "string" && typeof read.offset === "number");
		assert.ok(typeof read.limit === "number");
		assert.ok(typeof exec.command === "string" && typeof exec.timeoutMs === "number");
		assert.ok(typeof load.path === "string");
		const { signal } = new AbortController();

		await worker.api.partial({
			activity: "writing",
			tool: null,
			stage: String(partial.stage),
			messages: partial.messages,
		});
		await worker.api.search(search, signal);
		assert.deepEqual(
			await worker.api.read({ path: read.path, offset: read.offset, limit: read.limit }, signal),
			READ_ANSWER,
		);
		assert.deepEqual(
			await worker.api.exec({ command: exec.command, timeoutMs: exec.timeoutMs }, signal),
			EXEC_ANSWER,
		);
		assert.deepEqual(await worker.api.load({ path: load.path }, signal), LOAD_ANSWER);
		await worker.api.end({ outcome: "DONE", entries: end.entries, reason: null, detail: null });
		assert.equal(deadline.detail, "deadline");
		await worker.api.end({
			outcome: "FAILED",
			entries: [],
			reason: "DEADLINE",
			detail: "deadline",
		});

		assert.deepEqual(
			worker.received.map(({ method, path, authorization }) => [method, path, authorization]),
			[
				["PUT", "/turn/turn-1/partial", "Bearer turn-token"],
				["POST", "/turn/turn-1/search", "Bearer turn-token"],
				["POST", "/turn/turn-1/sandbox/read", "Bearer turn-token"],
				["POST", "/turn/turn-1/sandbox/exec", "Bearer turn-token"],
				["POST", "/turn/turn-1/sandbox/load", "Bearer turn-token"],
				["POST", "/turn/turn-1/end", "Bearer turn-token"],
				["POST", "/turn/turn-1/end", "Bearer turn-token"],
			],
		);
		assert.deepEqual(
			worker.received.map(({ body }) => body),
			[partial, search, read, exec, load, end, deadline],
		);
	} finally {
		worker.close();
	}
});

void test("the host reads the context the worker serves", async () => {
	const served = contract("context.response.json");
	const worker = await fakeWorker([{ status: 200, body: served }]);
	try {
		assert.deepEqual(await worker.api.context(), served);
		assert.deepEqual(worker.received[0]?.method, "GET");
	} finally {
		worker.close();
	}
});

void test("a 409 means the attempt lost its lease, on every call", async () => {
	const worker = await fakeWorker([{ status: 409 }, { status: 409 }, { status: 409 }]);
	try {
		await assert.rejects(
			worker.api.partial({ activity: "thinking", tool: null, stage: "Thinking…", messages: [] }),
			isLeaseLost,
		);
		await assert.rejects(
			worker.api.end({ outcome: "DONE", entries: [], reason: null, detail: null }),
			isLeaseLost,
		);
		await assert.rejects(worker.api.context(), isLeaseLost);
		// Not repeated, as an unanswered call would be.
		assert.equal(worker.received.length, 3);
	} finally {
		worker.close();
	}
});

void test("an end over the worker's size bound is refused at once, not repeated", async () => {
	const worker = await fakeWorker([{ status: 413 }]);
	try {
		await assert.rejects(
			worker.api.end({ outcome: "DONE", entries: [], reason: null, detail: null }),
			isTooLarge,
		);

		assert.equal(worker.received.length, 1);
	} finally {
		worker.close();
	}
});

const LINKS = [
	["link_result", "link-result.request.json", "resultId"],
	["link_problem", "link-problem.request.json", "problemId"],
	["show_feedback", "show-feedback.request.json", "feedbackId"],
] as const;

for (const [tool, fixture, idKey] of LINKS) {
	void test(`${tool} is admitted only when the worker echoes the id, and a refusal carries its reason`, async () => {
		const body = recordOf(contract(fixture));
		const id = String(body[idKey]);
		const worker = await fakeWorker([
			{ status: 200, body },
			{ status: 422, body: { status: 422, detail: "That feedback was already shown elsewhere." } },
			{ status: 200, body: { [idKey]: crypto.randomUUID() } },
		]);
		try {
			const { signal } = new AbortController();
			assert.deepEqual(await worker.api.link(tool, id, signal), { admitted: true });
			assert.deepEqual(await worker.api.link(tool, id, signal), {
				admitted: false,
				reason: "That feedback was already shown elsewhere.",
			});
			assert.deepEqual(await worker.api.link(tool, id, signal), { admitted: false, reason: null });
			const path = `/turn/turn-1/${fixture.replace(".request.json", "")}`;
			assert.deepEqual(
				worker.received.map((received) => [received.method, received.path, received.body]),
				[
					["POST", path, body],
					["POST", path, body],
					["POST", path, body],
				],
			);
		} finally {
			worker.close();
		}
	});
}

void test("a sandbox refusal is an answer, and an answer of another shape fails the call", async () => {
	const refusal = {
		ok: false,
		error: "NOT_FOUND",
		detail: "File not found: inputs/context/nothing.json",
	};
	const worker = await fakeWorker([
		{ status: 200, body: refusal },
		{
			status: 200,
			body: { ok: false, error: "UNAVAILABLE", detail: "No turn sandbox can start now." },
		},
		{ status: 200, body: { ok: true, content: "a shape no endpoint answers" } },
		{ status: 200, body: { ...EXEC_ANSWER, exitCode: "0" } },
		{ status: 200, body: { ...LOAD_ANSWER, resolved: { ref: "main" } } },
	]);
	try {
		const { signal } = new AbortController();
		assert.deepEqual(
			await worker.api.read({ path: "inputs/context/nothing.json" }, signal),
			refusal,
		);
		assert.deepEqual(await worker.api.exec({ command: "true" }, signal), {
			ok: false,
			error: "UNAVAILABLE",
			detail: "No turn sandbox can start now.",
		});
		await assert.rejects(worker.api.read({ path: "INDEX.md" }, signal), {
			message: "the worker sent a malformed sandbox/read answer",
		});
		await assert.rejects(worker.api.exec({ command: "true" }, signal), {
			message: "the worker sent a malformed sandbox/exec answer",
		});
		await assert.rejects(worker.api.load({ path: "repos/github.com/acme/api" }, signal), {
			message: "the worker sent a malformed sandbox/load answer",
		});
		assert.deepEqual(worker.received[0]?.body, { path: "inputs/context/nothing.json" });
	} finally {
		worker.close();
	}
});

void test("a command the worker did not answer is not run again", async () => {
	const worker = await fakeWorker([{ status: 503 }, { status: 409 }]);
	try {
		const { signal } = new AbortController();
		// A 503 is the worker's status, not a lost lease; only the 409 after it is.
		await assert.rejects(
			worker.api.exec({ command: "git log -n 1" }, signal),
			(error) => error instanceof WorkerStatusError && error.status === 503 && !isLeaseLost(error),
		);
		assert.equal(worker.received.length, 1);
		await assert.rejects(worker.api.read({ path: "INDEX.md" }, signal), isLeaseLost);
	} finally {
		worker.close();
	}
});

void test("a context read and an end the worker did not answer are sent again", async () => {
	const served = contract("context.response.json");
	const worker = await fakeWorker([
		{ status: 503 },
		{ status: 200, body: served },
		{ status: 503 },
		{ status: 204 },
	]);
	try {
		assert.deepEqual(await worker.api.context(), served);
		await worker.api.end({ outcome: "DONE", entries: [], reason: null, detail: null });

		assert.deepEqual(
			worker.received.map(({ method, path }) => `${method} ${path}`),
			[
				"GET /turn/turn-1/context",
				"GET /turn/turn-1/context",
				"POST /turn/turn-1/end",
				"POST /turn/turn-1/end",
			],
		);
		assert.deepEqual(worker.received[3]?.body, worker.received[2]?.body);
	} finally {
		worker.close();
	}
});

void test("a partial answer the worker did not answer is not sent again", async () => {
	const worker = await fakeWorker([{ status: 503 }]);
	try {
		await assert.rejects(
			worker.api.partial({ activity: "thinking", tool: null, stage: "Thinking…", messages: [] }),
		);

		assert.equal(worker.received.length, 1);
	} finally {
		worker.close();
	}
});
