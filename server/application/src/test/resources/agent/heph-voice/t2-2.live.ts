import assert from "node:assert/strict";
import test from "node:test";

import {
	ask,
	assertNoInternalTerm,
	PREPARED,
	skip,
	TERMS,
	TURN_MS,
	turn,
	WAITING,
} from "./harness.ts";

/** A remark about Hephaestus's own answer or the conversation (T2-2). */
const SELF_REFERENCE =
	/\b(?:my|the) (?:previous|earlier|last|above) (?:answer|reply|message)\b|\b(?:answer|reply|message) above\b|\balready (?:covered|answered|said|mentioned|explained)\b|\bas I (?:said|mentioned|explained)\b/iu;

function assertNoSelfReference(text: string) {
	assert.doesNotMatch(text, SELF_REFERENCE, `remarks on its own answer: ${text}`);
}

void test("T2-2: the oracle rejects staging's remark and accepts an answer that ends at its link", () => {
	for (const text of [
		"My previous answer already covered this.",
		"As I said, the 404 is the main problem.",
		"See the reply above for the details.",
	]) {
		assert.throws(() => assertNoSelfReference(text), text);
	}
	assertNoSelfReference(
		"The review of pull request #46 found that a missing file returns 200 instead of 404. Two practices couldn't be checked.",
	);
});

/** Staging's question of turn 115fee59 (T2-2), whose answer ended with "My previous answer already covered this". */
const T2_2_QUESTION = "What feedback did I get recently, and which item matters most?";

for (const where of ["app", "Slack"] as const) {
	void test(
		`T2-2 (${where}): an answer that links what it cites ends without a remark about itself`,
		{ skip, timeout: TURN_MS },
		async () => {
			const reply = await ask(
				`t2-2-no-self-reference-${where}`,
				turn(T2_2_QUESTION, true, WAITING, where),
				PREPARED,
			);
			assertNoSelfReference(reply.text);
			assert.ok(
				reply.linked.some(([tool]) => tool === "link_result" || tool === "show_feedback"),
				`does not link the observation or feedback it discusses: ${reply.text}`,
			);
			assertNoInternalTerm(TERMS, reply.text);
		},
	);
}
