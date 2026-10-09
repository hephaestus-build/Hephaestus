// The host's HTTP client for a turn against the worker's wire shape. The request bodies and the context are the files
// in heph-turn-contract/, which HephTurnControllerIntegrationTest sends to and reads from the real controller.

import assert from "node:assert/strict";
import { once } from "node:events";
import { readFileSync } from "node:fs";
import { createServer } from "node:http";
import test from "node:test";

import { isRecord, type PartialMessage } from "../../../main/resources/agent/heph-host-protocol.ts";
import {
	LeaseLostError,
	httpTurnApi,
	isTooLarge,
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

void test("the host sends its partial answer, searches, fetches and end in the shape the worker binds", async () => {
	const worker = await fakeWorker([
		{ status: 204 },
		{ status: 200, body: { hits: [] } },
		{ status: 200, body: { content: null } },
	]);
	try {
		const partial = recordOf(contract("partial.request.json"));
		const search = recordOf(contract("search.request.json"));
		const fetch = recordOf(contract("fetch-context.request.json"));
		const end = recordOf(contract("end.request.json"));
		const deadline = recordOf(contract("end-deadline.request.json"));
		assert.ok(isPartialMessages(partial.messages));
		assert.ok(isList(end.entries));
		assert.equal(typeof fetch.path, "string");
		const { signal } = new AbortController();

		await worker.api.partial({
			activity: "writing",
			tool: null,
			stage: String(partial.stage),
			messages: partial.messages,
		});
		await worker.api.search(search, signal);
		await worker.api.fetchContext(String(fetch.path), signal);
		await worker.api.end({ outcome: "DONE", entries: end.entries, reason: null, detail: null });
		await worker.api.end({
			outcome: "FAILED",
			entries: [],
			reason: "DEADLINE",
			detail: String(deadline.detail),
		});

		assert.deepEqual(
			worker.received.map(({ method, path, authorization }) => [method, path, authorization]),
			[
				["PUT", "/turn/turn-1/partial", "Bearer turn-token"],
				["POST", "/turn/turn-1/search", "Bearer turn-token"],
				["POST", "/turn/turn-1/fetch-context", "Bearer turn-token"],
				["POST", "/turn/turn-1/end", "Bearer turn-token"],
				["POST", "/turn/turn-1/end", "Bearer turn-token"],
			],
		);
		assert.deepEqual(
			worker.received.map(({ body }) => body),
			[partial, search, fetch, end, deadline],
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
			LeaseLostError,
		);
		await assert.rejects(
			worker.api.end({ outcome: "DONE", entries: [], reason: null, detail: null }),
			LeaseLostError,
		);
		await assert.rejects(worker.api.context(), LeaseLostError);
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

void test("a link is shown only when the worker admits the observation", async () => {
	const observationId = crypto.randomUUID();
	const worker = await fakeWorker([{ status: 200, body: { observationId } }, { status: 422 }]);
	try {
		const { signal } = new AbortController();
		assert.equal(await worker.api.linkObservation(observationId, signal), true);
		assert.equal(await worker.api.linkObservation(crypto.randomUUID(), signal), false);
		const [linked] = worker.received;
		assert.deepEqual(linked?.body, { observationId });
	} finally {
		worker.close();
	}
});

void test("a context resource the worker refuses fails the tool call with the worker's reason", async () => {
	const worker = await fakeWorker([
		{ status: 200, body: { content: { name: "acme" } } },
		{
			status: 422,
			body: { status: 422, detail: "inputs/context/nothing.json is not a context resource" },
		},
	]);
	try {
		const { signal } = new AbortController();
		assert.deepEqual(await worker.api.fetchContext("inputs/context/workspace.json", signal), {
			name: "acme",
		});
		await assert.rejects(worker.api.fetchContext("inputs/context/nothing.json", signal), {
			message: "inputs/context/nothing.json is not a context resource",
		});
		const [fetched] = worker.received;
		assert.deepEqual(fetched?.body, { path: "inputs/context/workspace.json" });
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
