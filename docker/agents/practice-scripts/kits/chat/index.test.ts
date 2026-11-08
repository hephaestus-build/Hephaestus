import assert from "node:assert/strict";
import { test } from "node:test";
import { threadRecordSchema } from "./records.ts";

void test("chat records preserve distinct native authors and nullable account identities", () => {
	const thread = {
		channel: "C123",
		threadTs: "1730000000.000001",
		messages: [
			{
				ts: "1730000000.000001",
				author: "U123",
				accountId: "9007199254740993",
				authorName: "Sam",
				text: "The rollout checks passed.",
			},
			{
				ts: "1730000000.000002",
				author: "U456",
				accountId: null,
				text: "I will check the metrics.",
				edited: true,
			},
		],
		messageCount: 2,
		truncated: false,
	};
	assert.deepEqual(threadRecordSchema.parse(thread), thread);
	assert.equal(
		threadRecordSchema.safeParse({
			...thread,
			messages: [{ ...thread.messages[0], accountId: 1 }],
		}).success,
		false,
	);
});
