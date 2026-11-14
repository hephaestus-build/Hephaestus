import assert from "node:assert/strict";
import test from "node:test";

import {
	ask,
	assertNoInternalTerm,
	CANNOT_LOOK,
	NO_FOLDER,
	sentences,
	skip,
	TERMS,
	TURN_MS,
	turn,
} from "./harness.ts";

/** The sentences before the first question, which is where an offer to go deeper starts. */
function beforeOffer(text: string): string[] {
	const all = sentences(text);
	const offer = all.findIndex((sentence) => sentence.endsWith("?"));
	return offer === -1 ? all : all.slice(0, offer);
}

void test(
	"without a context folder Hephaestus answers in a few sentences and says it cannot look",
	{ skip, timeout: TURN_MS },
	async () => {
		const reply = await ask(
			"no-context-folder",
			turn("Why is my last pull request not met?", false, NO_FOLDER),
		);
		assert.ok(
			beforeOffer(reply.text).length <= 3,
			`more than three sentences before an offer: ${reply.text}`,
		);
		assert.match(reply.text, CANNOT_LOOK, `does not say it cannot look: ${reply.text}`);
		assertNoInternalTerm(TERMS, reply.text);
	},
);
