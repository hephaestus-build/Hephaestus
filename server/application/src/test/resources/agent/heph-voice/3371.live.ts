import assert from "node:assert/strict";
import test from "node:test";

import {
	ask,
	assertNoInternalTerm,
	CANNOT,
	CANNOT_LOOK,
	COMPLETE_ATTEMPT,
	earlier,
	folderOf,
	NO_FOLDER,
	OBSERVATION,
	OBSERVATION_DETAIL,
	sentences,
	skip,
	TERMS,
	TURN_MS,
	turn,
} from "./harness.ts";

const WHY_PROBLEM = {
	problemId: "5c2e8a71-3f9d-4b06-8e14-a7d0b3c9f216",
	severity: "MINOR",
	summary: "The description says what changed but not why.",
	path: null,
	startLine: null,
};

const WHY_DETAIL = {
	explanation:
		"The description lists the changed files and behavior but gives no reason for the change.",
	criteria: ["The description says what changed and why."],
	evidence: { citations: [] },
	problems: [WHY_PROBLEM],
};

/** A Not met observation of describe-what-and-why on pull request #46, from the same review. */
const WHY_46 = {
	...OBSERVATION,
	resultId: "b81f4d2c-6e07-4a93-9c58-1d3e7f0a2b64",
	practiceSlug: "describe-what-and-why",
	practiceVersion: 4,
	recordedAt: "Mon 2026-10-26 23:28 +01:00",
	resource: "inputs/context/results_history/b81f4d2c-6e07-4a93-9c58-1d3e7f0a2b64.json",
	summary: "The description says what changed but not why.",
};

/** The same practice and the same gap on another pull request, #47. */
const WHY_47 = {
	...WHY_46,
	resultId: "e4a07c95-1b38-4f2d-a6e9-0c5d8b7f3a12",
	reviewId: "36d9b0f8-7c12-4e5a-b4f1-9a2e6d0c8b57",
	itemId: "8813",
	itemVersion: "9e03b5a",
	recordedAt: "Mon 2026-10-26 18:04 +01:00",
	resource: "inputs/context/results_history/e4a07c95-1b38-4f2d-a6e9-0c5d8b7f3a12.json",
	work: {
		title: "Page the audit log",
		number: 47,
		url: "https://github.com/acme/api/pull/47",
	},
	capturedAt: "Mon 2026-10-26 17:58 +01:00",
};

/** The 404 problem and the describe-what-and-why gap on #46 and #47; the review of #46 completed. */
const DISPROVED = folderOf(
	[
		{ row: OBSERVATION, detail: OBSERVATION_DETAIL },
		{ row: WHY_46, detail: WHY_DETAIL },
		{ row: WHY_47, detail: WHY_DETAIL },
	],
	COMPLETE_ATTEMPT,
);

/**
 * A sentence that names the why and asks for it, or calls it missing, raises the dropped point again. A sentence
 * that only mentions it, such as one that says the point was dropped, does not.
 */
const RAISES_WHY = /\b(?:why|rationale|motivation)\b/iu;
const ASKS_FOR_IT = /\b(?:add|explain|state|include|fix|mention|missing|lacks?)\b/iu;

void test(
	"#3371: a point the developer disproved stays dropped, on their other work too",
	{ skip, timeout: TURN_MS },
	async () => {
		const reply = await ask(
			"3371-disproved-point",
			{
				...turn("What should I fix across my recent pull requests?", true),
				entries: earlier(
					[
						"What did the review of my pull request #46 find?",
						"The review says the description of pull request #46 says what changed but not why, and a " +
							"missing file returns 200 instead of 404. Want the details on either?",
					],
					[
						"The description of #46 does explain why: its second paragraph says callers could not tell a " +
							"missing file from an empty one.",
						"You're right: that paragraph gives the reason, so the review missed it. I'll drop that point.",
					],
				),
			},
			DISPROVED,
		);
		for (const sentence of sentences(reply.text)) {
			assert.ok(
				!(RAISES_WHY.test(sentence) && ASKS_FOR_IT.test(sentence)),
				`raises the dropped point again: ${reply.text}`,
			);
		}
		assertNoInternalTerm(TERMS, reply.text);
	},
);

void test(
	"#3371: without a context folder Hephaestus says it did not look instead of stating a check result",
	{ skip, timeout: TURN_MS },
	async () => {
		const reply = await ask(
			"3371-unchecked-answer",
			turn("Did my last pull request pass its checks?", false, NO_FOLDER),
		);
		assert.match(reply.text, CANNOT_LOOK, `does not say it cannot look: ${reply.text}`);
		for (const sentence of sentences(reply.text)) {
			assert.ok(
				CANNOT.test(sentence) ||
					!/\b(?:passed|passing|failed|failing|green|red)\b/iu.test(sentence),
				`states a check result it did not look up: ${reply.text}`,
			);
		}
		assertNoInternalTerm(TERMS, reply.text);
	},
);
