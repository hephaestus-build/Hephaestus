import assert from "node:assert/strict";
import { test } from "node:test";

import {
	gatewayConnectionFailure,
	untilGatewayTakes,
} from "../../../main/resources/agent/gateway-connection.ts";
import { compositionClient } from "../../../main/resources/agent/review-composer-gateway.ts";

function refused(): TypeError {
	return new TypeError("fetch failed", { cause: { code: "ECONNREFUSED", syscall: "connect" } });
}

void test("a connect that failed on every address reached no server, and a reset may have", () => {
	const dualStack = new TypeError("fetch failed", {
		cause: {
			code: "ENETUNREACH",
			errors: [
				{ code: "ENETUNREACH", syscall: "connect" },
				{ code: "ECONNREFUSED", syscall: "connect" },
			],
		},
	});
	assert.equal(gatewayConnectionFailure(dualStack), "connection");
	assert.equal(gatewayConnectionFailure(refused()), "connection");
	assert.equal(
		gatewayConnectionFailure(new TypeError("fetch failed", { cause: { code: "ECONNRESET" } })),
		"response",
	);
	assert.equal(gatewayConnectionFailure(new Error("answered 500")), undefined);
});

void test("a refused call waits for the gateway, and a reset or the end of its work stops the wait", async () => {
	let calls = 0;
	const taken = await untilGatewayTakes(async () => {
		calls += 1;
		if (calls < 3) {
			throw refused();
		}
		return "taken";
	}, new AbortController().signal);
	assert.equal(taken, "taken");
	assert.equal(calls, 3);

	const reset = new TypeError("fetch failed", { cause: { code: "ECONNRESET" } });
	await assert.rejects(
		untilGatewayTakes(async () => {
			throw reset;
		}, new AbortController().signal),
		(error) => error === reset,
	);

	const ended = AbortSignal.timeout(1500);
	await assert.rejects(
		untilGatewayTakes(async () => {
			throw refused();
		}, ended),
	);
	assert.ok(ended.aborted);
});

void test("a composition's call waits while its server restarts", async () => {
	let calls = 0;
	const client = compositionClient({
		compositionUrl: "http://server.invalid/composition",
		proxyUrl: "http://server.invalid/internal/llm",
		headers: {},
		signal: new AbortController().signal,
		fetch: async () => {
			calls += 1;
			if (calls < 3) {
				throw refused();
			}
			return Response.json({ continue: false });
		},
	});
	await client.stop("COMPLETE");
	assert.equal(calls, 3);
});
