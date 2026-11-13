import assert from "node:assert/strict";

import { isRecord, type TurnEnd } from "../../../main/resources/agent/heph-host-protocol.ts";
import type { FakeWorker } from "./turn-worker.ts";

export const CUT_ANSWER = "Here is the first half of my ans";
export const WHOLE_ANSWER = `Here is the whole answer. ${"More of it. ".repeat(20)}`;

/** The native session and its partials must discard the failed attempt, also over the real gateway. */
export function assertRecoveredTurn(worker: FakeWorker, end: TurnEnd): void {
	assert.equal(end.outcome, "DONE");
	const assistants = worker.appended.flatMap((entry) => {
		if (entry.type !== "message" || entry.message.role !== "assistant") {
			return [];
		}
		const content: unknown = entry.message.content;
		assert.ok(Array.isArray(content));
		return [
			content
				.map((block: unknown) => (isRecord(block) && block.type === "text" ? block.text : ""))
				.join(""),
		];
	});
	assert.deepEqual(assistants, [WHOLE_ANSWER]);
	assert.ok(worker.appended.every((entry) => entry.type !== "context_edit"));
	const answering = worker.partials.filter((snapshot) =>
		JSON.stringify(snapshot).includes("whole answer"),
	);
	assert.ok(answering.length > 0);
	assert.ok(answering.every((snapshot) => !JSON.stringify(snapshot).includes("first half")));
}
