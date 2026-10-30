import assert from "node:assert/strict";
import { test } from "node:test";
import { ToolCallTimings } from "../../../main/resources/agent/pi-tool-timings.ts";

await test("overlapping tools retain each complete span and expose unfinished work", () => {
	const timing = new ToolCallTimings();
	timing.observe({ type: "tool_execution_start", toolCallId: "a" }, 10);
	timing.observe({ type: "tool_execution_start", toolCallId: "b" }, 20);
	timing.observe({ type: "tool_execution_end", toolCallId: "a" }, 40);
	assert.deepEqual(timing.snapshot(), {
		spans: [{ durationMs: 30, outcome: "completed" }],
		unfinished: 1,
		omitted: 0,
	});
	timing.observe({ type: "tool_execution_end", toolCallId: "b", isError: true }, 60);
	assert.deepEqual(timing.snapshot(), {
		spans: [
			{ durationMs: 30, outcome: "completed" },
			{ durationMs: 40, outcome: "failed" },
		],
		unfinished: 0,
		omitted: 0,
	});
});

await test("telemetry has a fixed bound and does not retain identifiers", () => {
	const timing = new ToolCallTimings();
	for (let index = 0; index < 5000; index += 1) {
		const toolCallId = String(index);
		timing.observe({ type: "tool_execution_start", toolCallId }, 10);
		timing.observe({ type: "tool_execution_end", toolCallId }, 11);
	}
	const snapshot = timing.snapshot();
	assert.equal(snapshot.spans.length, 4096);
	assert.equal(snapshot.omitted, 904);
	assert.equal(snapshot.unfinished, 0);
	assert.deepEqual(Object.keys(snapshot).toSorted(), ["omitted", "spans", "unfinished"]);
});
