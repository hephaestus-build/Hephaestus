import assert from "node:assert/strict";
import { once } from "node:events";
import { createServer, type Server } from "node:http";
import { test } from "node:test";

import { prepareFeedback } from "../../../main/resources/agent/pi-feedback-preparation.ts";

const digest = "a".repeat(64);
const payload = {
	reviewId: "11111111-1111-4111-8111-111111111111",
	jobId: "22222222-2222-4222-8222-222222222222",
	attempt: 1,
	publicComposition: true,
	publicationContext: null,
	basisResultIds: ["00000000-0000-4000-8000-000000000011"],
	composition: [
		{
			destination: "ON_WORK" as const,
			resultIds: ["00000000-0000-4000-8000-000000000011"],
			summary: null,
			notes: [],
			withheldReason: "COMPOSER_WITHHELD" as const,
			trace: { copies: [], withheld: [], replacesId: null, primaryResultId: null },
			page: null,
		},
	],
};

async function listen(server: Server): Promise<string> {
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	const address = server.address();
	assert.ok(address !== null && typeof address !== "string");
	return `http://127.0.0.1:${address.port}/internal/llm/prepare-feedback`;
}

void test("lost acknowledgement replays identical bytes, including a quiet review", async (t) => {
	const bodies: string[] = [];
	const requestPayload = structuredClone(payload);
	const original = structuredClone(requestPayload);
	const server = createServer((request, response) => {
		assert.equal(request.url, "/internal/llm/prepare-feedback");
		assert.equal(request.headers.authorization, "Bearer synthetic-token");
		let body = "";
		request.on("data", (chunk: unknown) => {
			assert.ok(Buffer.isBuffer(chunk));
			body += chunk.toString("utf8");
		});
		request.on("end", () => {
			bodies.push(body);
			if (bodies.length === 1) {
				const withheld = requestPayload.composition[0];
				assert.ok(withheld !== undefined);
				withheld.resultIds.push("00000000-0000-4000-8000-000000000012");
				response.destroy();
				return;
			}
			response.setHeader("Content-Type", "application/json");
			response.end(
				JSON.stringify({
					feedbackIds: [],
					preparedAt: "2026-10-14T12:00:00Z",
					contentDigest: digest,
				}),
			);
		});
	});
	t.after(() => {
		server.closeAllConnections();
		server.close();
	});
	const url = await listen(server);
	const result = await prepareFeedback({
		url,
		token: "synthetic-token",
		payload: requestPayload,
		timeoutMs: 10_000,
	});
	assert.deepEqual(result, {
		state: "PREPARED",
		receipt: { feedbackIds: [], preparedAt: "2026-10-14T12:00:00Z", contentDigest: digest },
	});
	assert.equal(bodies.length, 2);
	assert.equal(bodies[0], bodies[1]);
	assert.deepEqual(JSON.parse(bodies[0] ?? "null"), original);
});

void test("an explicit refusal, including an unreadable HTTP refusal, is final", async (t) => {
	for (const status of [409, 403]) {
		let calls = 0;
		const server = createServer((_request, response) => {
			calls += 1;
			response.writeHead(status, { "Content-Type": "application/json" });
			response.end(
				status === 200
					? JSON.stringify({ schemaVersion: 1, accepted: false, reasons: ["Refused"] })
					: "not JSON",
			);
		});
		t.after(() => {
			server.closeAllConnections();
			server.close();
		});
		const url = await listen(server);
		assert.deepEqual(
			await prepareFeedback({
				url,
				token: "synthetic-token",
				payload,
				timeoutMs: 10_000,
			}),
			{ state: "REFUSED" },
		);
		assert.equal(calls, 1);
	}
});

void test("an unanswered request remains unavailable without retrying a server still processing it", async (t) => {
	let calls = 0;
	const server = createServer(() => {
		calls += 1;
	});
	t.after(() => {
		server.closeAllConnections();
		server.close();
	});
	const url = await listen(server);
	assert.deepEqual(
		await prepareFeedback({
			url,
			token: "synthetic-token",
			payload,
			timeoutMs: 500,
		}),
		{ state: "UNAVAILABLE" },
	);
	assert.equal(calls, 1);
});

void test("a text refusal names its rule and field; without them it stays an untyped refusal", async (t) => {
	for (const [body, expected] of [
		[
			{ code: "FEEDBACK_TEXT_REFUSED", destination: "ON_WORK", rule: "LINK", field: "notes[0]" },
			{
				state: "REFUSED",
				publication: "FEEDBACK_TEXT_REFUSED",
				text: { rule: "LINK", field: "notes[0]" },
			},
		],
		[{ code: "FEEDBACK_TEXT_REFUSED", rule: "LINK" }, { state: "REFUSED" }],
		[{ code: "FEEDBACK_TEXT_REFUSED", rule: "SPELLING", field: "summary" }, { state: "REFUSED" }],
	] as const) {
		let calls = 0;
		const server = createServer((_request, response) => {
			calls += 1;
			response.writeHead(409, { "Content-Type": "application/problem+json" });
			response.end(JSON.stringify({ status: 409, ...body }));
		});
		t.after(() => {
			server.closeAllConnections();
			server.close();
		});
		const url = await listen(server);
		assert.deepEqual(
			await prepareFeedback({ url, token: "synthetic-token", payload, timeoutMs: 10_000 }),
			expected,
		);
		assert.equal(calls, 1);
	}
});

void test("a typed publication refusal is final and says that fresh history may help", async (t) => {
	for (const [body, expected] of [
		[
			{ code: "PUBLIC_HISTORY_CHANGED" },
			{ state: "REFUSED", publication: "PUBLIC_HISTORY_CHANGED" },
		],
		[
			{ code: "PUBLIC_DELIVERY_UNRESOLVED" },
			{ state: "REFUSED", publication: "PUBLIC_DELIVERY_UNRESOLVED" },
		],
		[{ code: "SOMETHING_ELSE" }, { state: "REFUSED" }],
	] as const) {
		let calls = 0;
		const server = createServer((_request, response) => {
			calls += 1;
			response.writeHead(409, { "Content-Type": "application/problem+json" });
			response.end(JSON.stringify({ status: 409, ...body }));
		});
		t.after(() => {
			server.closeAllConnections();
			server.close();
		});
		const url = await listen(server);
		assert.deepEqual(
			await prepareFeedback({ url, token: "synthetic-token", payload, timeoutMs: 10_000 }),
			expected,
		);
		assert.equal(calls, 1);
	}
});
