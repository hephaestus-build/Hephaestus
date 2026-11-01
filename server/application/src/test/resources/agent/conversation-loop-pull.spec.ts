import assert from "node:assert/strict";
import { once } from "node:events";
import { describe, it } from "node:test";
import { setTimeout as sleep } from "node:timers/promises";

import type { Assignment } from "../../../main/resources/agent/conversation-loop-protocol.ts";
import {
	assignmentGateway,
	runAssignments,
	type AssignmentGateway,
} from "../../../main/resources/agent/conversation-loop-pull.ts";

function assignment(turnId: string): Assignment {
	return {
		turnId,
		attempt: 1,
		token: `token-${turnId}`,
		renewAfterMs: 60_000,
		deadline: new Date(Date.now() + 60_000).toISOString(),
	};
}

void describe("runAssignments", () => {
	void it("pulls no new attempt while its turns fill the bound, and pulls again once one ends", async () => {
		const queue = ["a", "b", "c"];
		let pulls = 0;
		const gateway: AssignmentGateway = {
			async pull() {
				pulls += 1;
				const next = queue.shift();
				return next === undefined ? null : assignment(next);
			},
			async renew() {
				return "RUNNING";
			},
		};
		const finish = new Map<string, () => void>();
		const started: string[] = [];
		const stopping = new AbortController();
		const running = runAssignments(
			gateway,
			{
				start: async (turnId) => {
					started.push(turnId);
					const done = new AbortController();
					finish.set(turnId, () => done.abort());
					await once(done.signal, "abort");
				},
				stop() {
					// No attempt stops in this test.
				},
			},
			stopping.signal,
			() => assert.fail("no pull fails"),
			2,
			5,
		);
		await sleep(50);
		assert.deepEqual(started, ["a", "b"]);
		const pullsAtBound = pulls;
		await sleep(50);
		assert.equal(pulls, pullsAtBound, "a full loop does not pull");

		finish.get("a")?.();
		await sleep(50);
		assert.deepEqual(started, ["a", "b", "c"]);

		stopping.abort();
		finish.get("b")?.();
		finish.get("c")?.();
		await running;
	});

	void it("reads the credential again for each pull", async () => {
		const seen: string[] = [];
		const credentials = ["first", "rotated"];
		const original = globalThis.fetch;
		globalThis.fetch = async (_url: string | URL | Request, init?: RequestInit) => {
			seen.push(String(new Headers(init?.headers).get("Authorization")));
			return new Response(null, { status: 204 });
		};
		try {
			const gateway = assignmentGateway(
				"http://gateway.invalid",
				() => credentials.shift() ?? "none",
			);
			await gateway.pull(new AbortController().signal);
			await gateway.pull(new AbortController().signal);
		} finally {
			globalThis.fetch = original;
		}
		assert.deepEqual(seen, ["Bearer first", "Bearer rotated"]);
	});
});
