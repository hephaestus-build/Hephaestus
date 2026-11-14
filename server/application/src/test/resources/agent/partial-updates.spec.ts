import assert from "node:assert/strict";
import { test } from "node:test";
// oxlint-disable-next-line no-restricted-imports -- partial updates stream on a real interval, which the test samples
import { setTimeout as sleep } from "node:timers/promises";

import type { AgentSessionEvent } from "@earendil-works/pi-coding-agent";

import {
	MAX_PARTIAL_CHARS,
	type PartialUpdate,
} from "../../../main/resources/agent/heph-host-protocol.ts";
import { partialStream, partialUpdates, type TurnApi } from "../../../main/resources/agent/turn.ts";
import { applied, contextOf, fakeWorker } from "./turn-worker.ts";

/** Pi's assistant message, as a writing message's start carries it. */
type AssistantMessage = Extract<
	Extract<AgentSessionEvent, { type: "message_update" }>["assistantMessageEvent"],
	{ type: "start" }
>["partial"];

const START = Date.parse("2026-10-31T20:00:00Z");

/** A reader's snapshot after each update, or null once an update did not apply to it. */
function reader() {
	let kept: { sequence: number; text: string } | null = null;
	return (update: PartialUpdate): string | null => {
		const text = applied(kept, update);
		kept = text === null ? kept : { sequence: update.sequence, text };
		return text;
	};
}

const LONE_SURROGATE = /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u;

await test("a reader rebuilds each partial answer from the changes after the first whole snapshot", () => {
	const encode = partialUpdates();
	const read = reader();
	const first = encode('{"text":"Pin"}', START);
	const second = encode('{"text":"Pin the image"}', START + 250);

	assert.equal(first.baseSequence, 0, "the first update is a whole snapshot");
	assert.deepEqual(
		{ base: second.baseSequence, prefix: second.prefix, suffix: second.suffix, text: second.text },
		{ base: first.sequence, prefix: 12, suffix: 2, text: " the image" },
		"the second changes only what differs",
	);
	assert.equal(read(first), '{"text":"Pin"}');
	assert.equal(read(second), '{"text":"Pin the image"}');
});

await test("a reader that missed a change shows nothing new until the next whole snapshot, at most two seconds later", () => {
	const encode = partialUpdates();
	const read = reader();
	read(encode('{"text":"Pin"}', START));
	encode('{"text":"Pin the"}', START + 250);
	const change = encode('{"text":"Pin the image"}', START + 500);
	const whole = encode('{"text":"Pin the image is"}', START + 2000);

	assert.equal(read(change), null, "a change made from a missed one does not apply");
	assert.equal(whole.baseSequence, 0);
	assert.equal(read(whole), '{"text":"Pin the image is"}');
});

await test("a change never splits a surrogate pair at either end", () => {
	const encode = partialUpdates();
	const read = reader();
	read(encode('{"text":"Fertig 🙂 ja"}', START));
	// 🙂 and 🙃 share their high half: a naive prefix would end inside the pair.
	const change = encode('{"text":"Fertig 🙃 ja"}', START + 250);
	// Two emoji that share their low half: a naive suffix would start inside the pair.
	const other = encode('{"text":"Fertig 🙃 ja \u{1F600}"}', START + 500);
	const last = encode('{"text":"Fertig 🙃 ja \u{1F400}"}', START + 750);

	for (const update of [change, other, last]) {
		assert.ok(!LONE_SURROGATE.test(update.text), `a whole pair: ${JSON.stringify(update.text)}`);
		assert.ok(read(update) !== null);
	}
	assert.equal(read(encode('{"text":"done"}', START + 1000)), '{"text":"done"}');
});

await test("sequences grow even when the clock runs behind, so a change always names its own base", () => {
	const encode = partialUpdates();
	const first = encode('{"text":"a"}', START);
	const behind = encode('{"text":"ab"}', START - 60_000);

	assert.equal(behind.sequence, first.sequence + 1);
	assert.equal(behind.baseSequence, first.sequence);
});

await test("a partial answer that no reader would keep is not sent", async () => {
	const sent: PartialUpdate[] = [];
	const api: TurnApi = {
		...fakeWorker(contextOf([], "question")).api(),
		partial: async (update) => {
			sent.push(update);
		},
	};
	const partials = partialStream(api, 1);
	const message: AssistantMessage = {
		role: "assistant",
		content: [{ type: "text", text: "x".repeat(MAX_PARTIAL_CHARS) }],
		api: "openai-completions",
		provider: "hephaestus",
		model: "faux",
		usage: {
			input: 0,
			output: 0,
			cacheRead: 0,
			cacheWrite: 0,
			totalTokens: 0,
			cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
		},
		stopReason: "stop",
		timestamp: START,
	};
	const writing: AgentSessionEvent = {
		type: "message_update",
		message,
		assistantMessageEvent: { type: "start", partial: message },
	};
	partials.observe(writing);
	await sleep(50);
	partials.stop();
	await partials.settled();

	assert.deepEqual(sent, []);
});
