import assert from "node:assert/strict";
import test from "node:test";

import { ask, assertNoInternalTerm, sentences, skip, TERMS, TURN_MS, turn } from "./harness.ts";

void test(
	"E12: Hephaestus does not take an observation's time for when its review finished",
	{ skip, timeout: TURN_MS },
	async () => {
		const reply = await ask(
			"e12-review-time",
			turn("When did the review of my pull request #46 finish?", true),
		);
		assert.ok(
			reply.looked.some((entry) => entry.includes("results_history")),
			`answered without reading the observations: ${reply.looked.join(", ")}`,
		);
		for (const sentence of sentences(reply.text)) {
			assert.ok(
				!sentence.includes("23:26") || /record|observ/iu.test(sentence),
				`gives 23:26 as something other than when an observation was recorded: ${reply.text}`,
			);
		}
		assertNoInternalTerm(TERMS, reply.text);
	},
);

void test(
	"E12: Hephaestus gives a review's start time without a cause that no record shows",
	{ skip, timeout: TURN_MS },
	async () => {
		const reply = await ask(
			"e12-review-start",
			turn("What did the review of my pull request #46 find, and when did it start?", true),
		);
		assert.doesNotMatch(
			reply.text,
			/\b(?:when|because|after|as soon as) you (?:opened|created|pushed|submitted|updated)\b/iu,
			`names a start cause that no record shows: ${reply.text}`,
		);
		assertNoInternalTerm(TERMS, reply.text);
	},
);
