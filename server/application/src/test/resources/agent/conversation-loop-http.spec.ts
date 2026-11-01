// The conversation loop's HTTP client for an attempt against the worker's wire shape. The request bodies and the context
// are the files in heph-turn-contract/, which ConversationTurnControllerIntegrationTest sends to and reads from the real
// controller.

import assert from "node:assert/strict";
import { once } from "node:events";
import { readFileSync } from "node:fs";
import { createServer } from "node:http";
import test from "node:test";

import {
	isRecord,
	type PartialMessage,
} from "../../../main/resources/agent/conversation-loop-protocol.ts";
import {
	httpTurnApi,
	isLeaseLost,
	isTooLarge,
	WorkerStatusError,
} from "../../../main/resources/agent/conversation-loop.ts";

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
	headers?: Record<string, string>;
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
				response.writeHead(answer.status, answer.headers).end();
			} else {
				response
					.writeHead(answer.status, { "content-type": "application/json", ...answer.headers })
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

const GENERATION = "4d1f2c3b-8e7a-4b6c-9d0e-1f2a3b4c5d6e";
const DOCUMENT = '{\n  "pullRequests": []\n}';
/** A captured document, as `documents/read` answers it: the text, under the context it was built from. */
function document(text = DOCUMENT, generation = GENERATION): Answer {
	return { status: 200, body: { text }, headers: { "X-Context-Generation": generation } };
}

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

void test("the context folder's calls wait for their turn budget instead of the shorter worker-request timeout", async (context) => {
	// Every documents and tools call goes through one request without a timeout; a read stands for them.
	const worker = await fakeWorker([document()]);
	context.mock.method(AbortSignal, "timeout", () =>
		AbortSignal.abort(new Error("request timeout")),
	);
	try {
		const answer = await worker.api.read({ path: "INDEX.md" }, new AbortController().signal);
		assert.equal(answer.ok, true);
	} finally {
		worker.close();
	}
});

void test("the loop sends its partial answer, searches, document reads and end in the shape the worker binds", async () => {
	const worker = await fakeWorker([
		{ status: 204 },
		{ status: 200, body: { hits: [] } },
		document(),
		{ status: 204 },
		{ status: 204 },
	]);
	try {
		const partial = recordOf(contract("partial.request.json"));
		const search = recordOf(contract("search.request.json"));
		const read = recordOf(contract("documents-read.request.json"));
		const end = recordOf(contract("end.request.json"));
		const deadline = recordOf(contract("end-deadline.request.json"));
		assert.ok(isPartialMessages(partial.messages));
		assert.ok(isList(end.entries));
		assert.ok(typeof read.path === "string");
		const { signal } = new AbortController();
		const snapshot = {
			activity: "writing" as const,
			tool: null,
			stage: String(partial.stage),
			messages: partial.messages,
		};

		await worker.api.partial(snapshot);
		await worker.api.search(search, signal);
		const answer = await worker.api.read({ path: read.path, offset: 1, limit: 200 }, signal);
		assert.ok(answer.ok && answer.text.includes('"pullRequests"'), JSON.stringify(answer));
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
				["POST", "/turn/turn-1/documents/read", "Bearer turn-token"],
				["POST", "/turn/turn-1/end", "Bearer turn-token"],
				["POST", "/turn/turn-1/end", "Bearer turn-token"],
			],
		);
		// Each end reports the partial answer the worker accepted last, which readers may have seen.
		assert.deepEqual(
			worker.received.map(({ body }) => body),
			[
				partial,
				search,
				read,
				{ ...end, lastPartial: partial },
				{ ...deadline, lastPartial: partial },
			],
		);
	} finally {
		worker.close();
	}
});

void test("the loop reads the context the worker serves, with the release's system prompt", async () => {
	const served = recordOf(contract("context.response.json"));
	assert.equal(typeof served.systemPrompt, "string");
	// The loop reads no generation from the context: documents and tools Jobs carry their own.
	const { generation: _generation, ...read } = served;
	const worker = await fakeWorker([{ status: 200, body: served }]);
	try {
		assert.deepEqual(await worker.api.context(), read);
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

void test("a missing document is an answer, and a document of another shape fails the call", async () => {
	const worker = await fakeWorker([
		{ status: 404 },
		{
			status: 200,
			body: { content: "a shape no endpoint answers" },
			headers: { "X-Context-Generation": GENERATION },
		},
		{ status: 200, body: { text: DOCUMENT } },
	]);
	try {
		const { signal } = new AbortController();
		assert.deepEqual(await worker.api.read({ path: "inputs/context/nothing.json" }, signal), {
			ok: false,
			error: "NOT_FOUND",
			detail: "This context document is unavailable.",
		});
		await assert.rejects(worker.api.read({ path: "INDEX.md" }, signal), {
			message: "The gateway sent a malformed context document",
		});
		await assert.rejects(worker.api.read({ path: "FIELDS.md" }, signal), {
			message: "The gateway sent a malformed context document",
		});
		assert.deepEqual(worker.received[0]?.body, { path: "inputs/context/nothing.json" });
	} finally {
		worker.close();
	}
});

void test("a document of a changed context drops what the attempt read and says so", async () => {
	const worker = await fakeWorker([
		document(),
		document("# Fields", "9e8d7c6b-5a4f-4e3d-8c2b-1a0f9e8d7c6b"),
	]);
	try {
		const { signal } = new AbortController();
		const read = await worker.api.read({ path: "INDEX.md" }, signal);
		assert.equal(read.ok, true);

		assert.deepEqual(await worker.api.read({ path: "FIELDS.md" }, signal), {
			ok: false,
			error: "CONTEXT_CHANGED",
			detail: "The context folder changed. Read it again; repositories must be loaded again.",
		});
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
		{ status: 204 },
		{ status: 503 },
		{ status: 204 },
	]);
	const snapshot = { activity: "thinking" as const, tool: null, stage: "Thinking…", messages: [] };
	try {
		await worker.api.context();
		await worker.api.partial(snapshot);
		await worker.api.end({ outcome: "DONE", entries: [], reason: null, detail: null });

		assert.deepEqual(
			worker.received.map(({ method, path }) => `${method} ${path}`),
			[
				"GET /turn/turn-1/context",
				"GET /turn/turn-1/context",
				"PUT /turn/turn-1/partial",
				"POST /turn/turn-1/end",
				"POST /turn/turn-1/end",
			],
		);
		assert.deepEqual(worker.received[4]?.body, worker.received[3]?.body);
		assert.deepEqual(recordOf(worker.received[3]?.body).lastPartial, snapshot);
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
