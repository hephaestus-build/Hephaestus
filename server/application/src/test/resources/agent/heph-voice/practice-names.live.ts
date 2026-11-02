import assert from "node:assert/strict";
import test from "node:test";

import { ask, assertNoInternalTerm, OBSERVATION, skip, TERMS, TURN_MS, turn } from "./harness.ts";

// The harness answers no jq, so ResultHistoryContentSourceTest and ReviewAttemptsContentSourceTest own the field paths.
void test(
	"Hephaestus names a practice and says what a running review has recorded so far",
	{ skip, timeout: TURN_MS },
	async () => {
		const reply = await ask(
			"practice-names",
			turn(
				"What feedback did I get on my pull request Return 404 for a missing file? Is its review still running?",
				true,
			),
		);
		assert.ok(
			!reply.text.includes(OBSERVATION.practiceSlug),
			`names the practice by its slug: ${reply.text}`,
		);
		assert.match(reply.text, /\b200\b/u, `leaves out the recorded problem: ${reply.text}`);
		assert.match(
			reply.text,
			/\b(?:running|not finished|hasn't finished|still going)\b/iu,
			`does not say that the review still runs: ${reply.text}`,
		);
		assert.doesNotMatch(
			reply.text,
			/\bno detail\b|\bno record\b|(?:does not|doesn't) appear/iu,
			`claims that a record is missing: ${reply.text}`,
		);
		assertNoInternalTerm(TERMS, reply.text);
	},
);
