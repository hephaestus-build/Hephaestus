// The host's HTTP client for a turn against the worker's wire shape. The request bodies and the context are the files
// in heph-turn-contract/, which HephTurnControllerIntegrationTest sends to and reads from the real controller.

import assert from "node:assert/strict";
import { once } from "node:events";
import { readFileSync } from "node:fs";
import { createServer } from "node:http";
import test from "node:test";

import {
	isRecord,
	type PartialMessage,
	type PartialUpdate,
	type StopReason,
} from "../../../main/resources/agent/heph-host-protocol.ts";
import {
	declaredSize,
	httpTurnApi,
	isLeaseLost,
	isTooLarge,
	WorkerStatusError,
	partialUpdates,
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

/**
 * A worker that answers each request with the next of `answers` and records what it received, and each stop that the
 * api reports.
 */
async function fakeWorker(answers: Answer[], unknownKeyDelayMs = 0) {
	const received: Received[] = [];
	const stops: StopReason[] = [];
	const server = createServer((request, response) => {
		// Another local process can probe a newly opened port. It carries no credential.
		if (request.headers.authorization === undefined) {
			response.writeHead(401).end();
			return;
		}
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
		stops,
		// Repeats wait no time here; the delays are the host's to choose.
		api: httpTurnApi(
			`http://127.0.0.1:${address.port}`,
			"turn-1",
			"turn-token",
			(reason) => {
				stops.push(reason);
			},
			[0, 0, 0],
			unknownKeyDelayMs,
		),
		close: () => server.close(),
	};
}

/** A whole partial answer of nothing written yet, as one update. */
const WHOLE: PartialUpdate = {
	sequence: 1,
	baseSequence: 0,
	prefix: 0,
	suffix: 0,
	text: JSON.stringify({ activity: "thinking", tool: null, stage: "Thinking…", messages: [] }),
};

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

void test("the host sends its partial answer, searches, document reads and end in the shape the worker binds", async () => {
	const worker = await fakeWorker([
		{ status: 204 },
		{ status: 200, body: { hits: [] } },
		{ status: 200, body: READ_ANSWER },
	]);
	try {
		const partial = recordOf(contract("partial.request.json"));
		const search = recordOf(contract("search.request.json"));
		const read = recordOf(contract("sandbox-document.request.json"));
		const end = recordOf(contract("end.request.json"));
		const deadline = recordOf(contract("end-deadline.request.json"));
		// The server binds the update; its text is the whole snapshot, as the loop's encoder writes it.
		const snapshot = recordOf(JSON.parse(String(partial.text)));
		assert.ok(isPartialMessages(snapshot.messages));
		assert.ok(isList(end.entries));
		assert.ok(typeof read.path === "string" && typeof read.offset === "number");
		assert.ok(typeof read.limit === "number");
		const { signal } = new AbortController();

		await worker.api.partial(
			partialUpdates()(
				JSON.stringify({
					activity: "writing",
					tool: null,
					stage: String(snapshot.stage),
					messages: snapshot.messages,
				}),
				Number(partial.sequence),
			),
		);
		await worker.api.search(search, signal);
		assert.deepEqual(
			await worker.api.read({ path: read.path, offset: read.offset, limit: read.limit }, signal),
			READ_ANSWER,
		);
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
				["PUT", "/turn/turn-1/partial?first=true", "Bearer turn-token"],
				["POST", "/turn/turn-1/search", "Bearer turn-token"],
				["POST", "/turn/turn-1/sandbox/document", "Bearer turn-token"],
				["POST", "/turn/turn-1/end", "Bearer turn-token"],
				["POST", "/turn/turn-1/end", "Bearer turn-token"],
			],
		);
		assert.deepEqual(
			worker.received.map(({ body }) => body),
			[partial, search, read, end, deadline],
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

void test("a 409 without a stop means the attempt lost its lease, on every call", async () => {
	const worker = await fakeWorker([{ status: 409 }, { status: 409 }, { status: 409 }]);
	try {
		await assert.rejects(worker.api.partial(WHOLE), isLeaseLost);
		await assert.rejects(
			worker.api.end({ outcome: "DONE", entries: [], reason: null, detail: null }),
			isLeaseLost,
		);
		await assert.rejects(worker.api.context(), isLeaseLost);
		// Not repeated, as an unanswered call would be.
		assert.equal(worker.received.length, 3);
		assert.deepEqual(worker.stops, ["lease", "lease", "lease"]);
	} finally {
		worker.close();
	}
});

/** The worker's ProblemDetail for an attempt that must stop. */
function problem(stop: unknown): Answer {
	return { status: 409, body: { type: "about:blank", title: "Conflict", status: 409, stop } };
}

void test("a 409 that names a stop reports that stop before the call fails as no longer the attempt's", async () => {
	const worker = await fakeWorker([problem("developer"), problem("deadline"), problem("bored")]);
	try {
		const { signal } = new AbortController();
		await assert.rejects(worker.api.read({ path: "INDEX.md" }, signal), isLeaseLost);
		await assert.rejects(worker.api.context(), {
			message: "turn turn-1 must stop: deadline",
		});
		await assert.rejects(worker.api.search({ query: "pin" }, signal), isLeaseLost);

		assert.equal(worker.received.length, 3);
		assert.deepEqual(worker.stops, ["developer", "deadline", "lease"]);
	} finally {
		worker.close();
	}
});

void test("a 401 is sent once more after a wait, and a second 401 fails the call", async () => {
	const served = contract("context.response.json");
	const worker = await fakeWorker(
		[{ status: 401 }, { status: 200, body: served }, { status: 401 }, { status: 401 }],
		20,
	);
	try {
		const asked = performance.now();
		assert.deepEqual(await worker.api.context(), served);
		const waited = performance.now() - asked;
		await assert.rejects(
			worker.api.end({ outcome: "DONE", entries: [], reason: null, detail: null }),
			(error) => error instanceof WorkerStatusError && error.status === 401,
		);

		// A timer may fire a millisecond before the clock reads its delay.
		assert.ok(waited >= 19, `waited ${waited} ms before the second try`);
		assert.deepEqual(
			worker.received.map(({ method, path }) => `${method} ${path}`),
			[
				"GET /turn/turn-1/context",
				"GET /turn/turn-1/context",
				"POST /turn/turn-1/end",
				"POST /turn/turn-1/end",
			],
		);
	} finally {
		worker.close();
	}
});

void test("only the first partial answer the worker took is marked first", async () => {
	const worker = await fakeWorker([{ status: 503 }, { status: 204 }, { status: 204 }]);
	try {
		await assert.rejects(worker.api.partial(WHOLE));
		await worker.api.partial(WHOLE);
		await worker.api.partial(WHOLE);

		assert.deepEqual(
			worker.received.map(({ method, path }) => `${method} ${path}`),
			[
				"PUT /turn/turn-1/partial?first=true",
				"PUT /turn/turn-1/partial?first=true",
				"PUT /turn/turn-1/partial",
			],
		);
	} finally {
		worker.close();
	}
});

void test("a lease renewal posts to the turn's lease, and a stop or a failure it gets does not fail it", async () => {
	const worker = await fakeWorker([
		{ status: 204 },
		{ status: 409, body: { type: "about:blank", status: 409, stop: "developer" } },
		{ status: 409 },
		{ status: 500 },
	]);
	try {
		await worker.api.lease();
		await worker.api.lease();
		await worker.api.lease();
		await worker.api.lease();

		assert.deepEqual(
			worker.received.map(({ method, path, authorization }) => [method, path, authorization]),
			Array.from({ length: 4 }, () => ["POST", "/turn/turn-1/lease", "Bearer turn-token"]),
		);
		assert.deepEqual(worker.stops, ["developer", "lease"]);
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
			body: { ok: false, error: "UNAVAILABLE", detail: "No tools can start now." },
		},
		{ status: 200, body: { ok: true, content: "a shape no endpoint answers" } },
		{ status: 200, body: { ok: true, id: "hephaestus-tools-1", host: "10.0.0.1", port: "7100" } },
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
			detail: "No tools can start now.",
		});
		await assert.rejects(worker.api.read({ path: "INDEX.md" }, signal), {
			message: "the server sent a malformed sandbox/document answer",
		});
		await assert.rejects(worker.api.exec({ command: "true" }, signal), {
			message: "the server sent a malformed sandbox/bind answer",
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
		assert.deepEqual(worker.stops, ["lease"]);
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

void test("a gateway limit does not end a turn: a refused context read and end are sent again", async () => {
	const served = contract("context.response.json");
	const worker = await fakeWorker([
		{ status: 429 },
		{ status: 200, body: served },
		{ status: 429 },
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
		await assert.rejects(worker.api.partial(WHOLE));

		assert.equal(worker.received.length, 1);
	} finally {
		worker.close();
	}
});

/** The size that headers with this content-length declare; none when `length` is `undefined`. */
function sizeOf(length?: string) {
	return declaredSize(new Headers(length === undefined ? {} : { "content-length": length }));
}

void test("a size the worker did not declare is no size: missing, empty and malformed lengths are refused", () => {
	assert.equal(sizeOf(), null);
	assert.equal(sizeOf(""), null);
	assert.equal(sizeOf("-1"), null);
	assert.equal(sizeOf("1e3"), null);
	assert.equal(sizeOf("0"), 0);
	assert.equal(sizeOf("4096"), 4096);
});

/** A worker that answers a load with an archive, `sized` or chunked, and every other call with a refusal. */
async function archiveWorker(sized: boolean) {
	const about = Buffer.from(
		JSON.stringify({
			ok: true,
			path: "repos/github.com/acme/api",
			resolved: { ref: "main", sha: "abc", cite: "@abc" },
			note: "Loaded.",
			summary: [],
		}),
	).toString("base64url");
	const archive = Buffer.alloc(1024);
	const server = createServer((request, response) => {
		request.resume();
		request.on("end", () => {
			if (request.url?.endsWith("/sandbox/load") !== true) {
				response
					.writeHead(200, { "content-type": "application/json" })
					.end(JSON.stringify({ ok: false, error: "UNAVAILABLE", detail: "No tools now." }));
				return;
			}
			response.writeHead(200, {
				"content-type": "application/x-tar",
				"hephaestus-load": about,
				"hephaestus-load-sha256": "0".repeat(64),
				...(sized ? { "content-length": String(archive.length) } : {}),
			});
			// Without a content-length, Node sends the body chunked.
			response.write(archive.subarray(0, 512));
			response.end(archive.subarray(512));
		});
	});
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	const address = server.address();
	assert.ok(address !== null && typeof address !== "string");
	return {
		api: httpTurnApi(
			`http://127.0.0.1:${address.port}`,
			"turn-1",
			"turn-token",
			() => undefined,
			[0, 0, 0],
		),
		close: () => server.close(),
	};
}

void test("a load archive with its size goes on to bind its tools Pod, and waits on no unread copy of itself", async () => {
	const worker = await archiveWorker(true);
	try {
		assert.deepEqual(
			await worker.api.load({ path: "repos/github.com/acme/api" }, AbortSignal.timeout(10_000)),
			{
				ok: false,
				error: "UNAVAILABLE",
				detail: "No tools now.",
			},
		);
	} finally {
		worker.close();
	}
});

void test("a load archive that comes chunked, with no declared size, fails the load", async () => {
	const worker = await archiveWorker(false);
	try {
		await assert.rejects(
			worker.api.load({ path: "repos/github.com/acme/api" }, AbortSignal.timeout(10_000)),
			/malformed sandbox\/load answer/u,
		);
	} finally {
		worker.close();
	}
});
