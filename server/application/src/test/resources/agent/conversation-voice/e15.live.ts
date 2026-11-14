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

void test(
	"E15: Hephaestus tries feedback it may not show once and says nothing about why",
	{ skip, timeout: TURN_MS },
	async () => {
		const reply = await ask(
			"e15-refused-feedback",
			turn("Anything new for me?", true, WAITING),
			PREPARED,
			async (tool) =>
				tool === "show_feedback"
					? {
							admitted: false,
							reason: "it is not conversation feedback that is ready for the developer now",
						}
					: { admitted: true },
		);
		const shows = reply.linked.filter(([tool]) => tool === "show_feedback");
		assert.ok(shows.length <= 1, `called show_feedback ${shows.length} times: ${reply.text}`);
		assert.doesNotMatch(
			reply.text,
			/\b(?:queued|release[ds]?|held back|withheld|refused)\b|can(?:'|’)?t (?:release|show)/iu,
			`tells them about feedback it could not show: ${reply.text}`,
		);
		assertNoInternalTerm(TERMS, reply.text);
	},
);
