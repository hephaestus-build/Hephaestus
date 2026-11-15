import assert from "node:assert/strict";
import test from "node:test";

import { ask, assertNoInternalTerm, skip, TERMS, TURN_MS, turn } from "./harness.ts";

void test(
	"O8: Hephaestus answers a plain question about one problem briefly",
	{ skip, timeout: TURN_MS },
	async () => {
		const reply = await ask(
			"o8-short-answer",
			turn("What should I fix on pull request #46?", true),
		);
		assert.ok(reply.words <= 120, `${reply.words} words: ${reply.text}`);
		assert.match(reply.text, /404/u, `does not name the problem: ${reply.text}`);
		assertNoInternalTerm(TERMS, reply.text);
	},
);
