import assert from "node:assert/strict";
import test from "node:test";

import { KINDS } from "../../../main/resources/agent/driver-kinds.ts";
import type { RunContext } from "../../../main/resources/agent/driver.ts";

/** A review_compose run whose gateway has nothing to compose and closes with this answer. */
function run(close: Response): { context: RunContext; paths: string[] } {
	const paths: string[] = [];
	const fetch: typeof globalThis.fetch = async (input) => {
		const path = new URL(input instanceof Request ? input.url : input).pathname;
		paths.push(path);
		return path.endsWith("/next") ? new Response(null, { status: 204 }) : close;
	};
	globalThis.fetch = fetch;
	return {
		context: {
			assignment: {
				runId: "run-1",
				epoch: 2,
				kind: "REVIEW_COMPOSE",
				leasedUntil: Date.now() + 60_000,
				deadline: null,
			},
			gateway: { url: "http://gateway:8081", headers: { authorization: "Bearer token" } },
			signal: new AbortController().signal,
		},
		paths,
	};
}

void test("a composition that decides every destination completes its run", async () => {
	const runner = KINDS.REVIEW_COMPOSE;
	assert.ok(runner);
	const { context, paths } = run(Response.json({ handedOn: null }));

	assert.deepEqual(await runner(context), { leaseLost: false, outcome: "completed" });
	assert.deepEqual(paths, [
		"/internal/agent/runs/run-1/composition/next",
		"/internal/agent/runs/run-1/composition/close",
	]);
});

void test("a composition that a passing cause stopped hands its run on", async () => {
	const runner = KINDS.REVIEW_COMPOSE;
	assert.ok(runner);
	const { context } = run(Response.json({ handedOn: "PROVIDER" }));

	assert.deepEqual(await runner(context), { leaseLost: false, outcome: "handed_on" });
});

void test("a composition whose run lost its lease ends nothing", async () => {
	const runner = KINDS.REVIEW_COMPOSE;
	assert.ok(runner);
	const { context } = run(new Response(null, { status: 409 }));

	assert.deepEqual(await runner(context), { leaseLost: true });
});
