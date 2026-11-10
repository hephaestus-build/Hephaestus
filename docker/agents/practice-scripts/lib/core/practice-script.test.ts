import assert from "node:assert/strict";
import { describe, test } from "node:test";

import {
	isPracticeScriptDefinition,
	readRecordJson,
	reasonOf,
	UnratedError,
	type Evidence,
} from "./practice-script.ts";

void describe("the context helpers", () => {
	void test("an absent record is null, and malformed JSON or a failed read stops the program", async () => {
		const records: Record<string, string> = {
			"metadata.json": '{"pr_number": 7}',
			"notes.md": "# Notes",
		};
		const evidence: Pick<Evidence, "record"> = {
			record: async (name) => {
				if (name === "broken.json") {
					throw new UnratedError("unavailable", "the read failed");
				}
				const text = records[name];
				return text === undefined ? { status: "ABSENT" } : { status: "FOUND", text };
			},
		};
		assert.deepEqual(await readRecordJson(evidence, "metadata.json"), { pr_number: 7 });
		await assert.rejects(readRecordJson(evidence, "notes.md"), {
			name: "UnratedError",
			reason: "unavailable",
			message: "notes.md is not valid JSON",
		});
		assert.equal(await readRecordJson(evidence, "comments.json"), null);
		await assert.rejects(readRecordJson(evidence, "broken.json"), {
			name: "UnratedError",
			reason: "unavailable",
		});
	});

	void test("a failed call is unrated for its own reason", () => {
		assert.equal(reasonOf(new UnratedError("refused")), "refused");
		assert.equal(reasonOf(new DOMException("late", "TimeoutError")), "deadline");
		assert.equal(reasonOf(new DOMException("stopped", "AbortError")), "deadline");
		assert.equal(reasonOf(new Error("the read failed")), "error");
		assert.equal(reasonOf("thrown text"), "error");
	});

	void test("a definition is a meta object and a run function; anything else is not", () => {
		assert.equal(isPracticeScriptDefinition({ meta: {}, run: async () => ({}) }), true);
		assert.equal(
			isPracticeScriptDefinition(async () => ({})),
			false,
		);
		assert.equal(isPracticeScriptDefinition({ meta: {}, run: "review" }), false);
		assert.equal(isPracticeScriptDefinition({ run: async () => ({}) }), false);
	});
});
