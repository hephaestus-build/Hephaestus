import assert from "node:assert/strict";
import test from "node:test";

import {
	ask,
	assertNoInternalTerm,
	CANNOT,
	earlier,
	folderOf,
	OBSERVATION,
	OBSERVATION_DETAIL,
	RUNNING_ATTEMPT,
	sentences,
	skip,
	TERMS,
	TURN_MS,
	turn,
} from "./harness.ts";

const TESTS_RESULT_ID = "7a3e9c14-2b6f-4d08-9e51-c8f0a2d6b937";

/** A Met observation of the tests practice on the same pull request, from the same review. */
const TESTS_OBSERVATION = {
	...OBSERVATION,
	resultId: TESTS_RESULT_ID,
	practiceSlug: "ships-tests-with-the-change",
	practiceVersion: 2,
	outcome: "MET",
	recordedAt: "Mon 2026-10-26 23:31 +01:00",
	resource: `inputs/context/results_history/${TESTS_RESULT_ID}.json`,
	summary: "The change adds a test for the missing-file path next to the handler.",
};

const TESTS_DETAIL = {
	explanation:
		"src/api/files.test.ts adds a case for the missing-file path that the change touches.",
	criteria: ["A behavior change ships with a test that exercises it."],
	evidence: { citations: [{ path: "src/api/files.test.ts", startLine: 18, endLine: 31 }] },
	problems: [],
};

/** The same review once it ended: two practices judged, two it could not check. */
const PARTIAL_ATTEMPT = {
	...RUNNING_ATTEMPT,
	status: "COMPLETED",
	processingStatus: "DONE",
	completedAt: "Mon 2026-10-26 23:44 +01:00",
	reviewStatus: "PARTIAL",
	reviewStopReason: "RESULT_WITHHELD",
	assignmentCounts: { ...RUNNING_ATTEMPT.assignmentCounts, judged: 2, withheld: 2, pending: 0 },
};

/** The ended review: one Met and one Not met observation, and two practices it could not check. */
const ENDED = folderOf(
	[
		{ row: OBSERVATION, detail: OBSERVATION_DETAIL },
		{ row: TESTS_OBSERVATION, detail: TESTS_DETAIL },
	],
	PARTIAL_ATTEMPT,
);

void test(
	"W2-17: Hephaestus says a withheld practice could not be checked and never calls the rest clean",
	{ skip, timeout: TURN_MS },
	async () => {
		const reply = await ask(
			"w2-17-withheld",
			turn("What did the review of my pull request #46 find? Is the rest fine?", true),
			ENDED,
		);
		for (const sentence of sentences(reply.text)) {
			assert.ok(
				CANNOT.test(sentence) ||
					!/\b(?:everything else|the rest|all (?:the )?other)\b[^.!?]*\b(?:clean|fine|good|met|passed)\b/iu.test(
						sentence,
					),
				`calls the rest clean: ${reply.text}`,
			);
		}
		assert.doesNotMatch(
			reply.text,
			/not yet (?:assessed|judged|checked)/iu,
			`calls a withheld practice not yet assessed: ${reply.text}`,
		);
		assertNoInternalTerm(TERMS, reply.text);
	},
);

void test(
	"W2-17: Hephaestus claims no history that the observations it read cannot show",
	{ skip, timeout: TURN_MS },
	async () => {
		const reply = await ask(
			"w2-17-history",
			{
				...turn("Is this the first time I met that practice?", true),
				entries: earlier([
					"What did the review of my pull request #46 find?",
					"Pull request #46 ships a test for the missing-file path, so it met the tests practice. " +
						"A missing file still returns 200 instead of 404. Want the details on the 404?",
				]),
			},
			ENDED,
		);
		for (const sentence of sentences(reply.text)) {
			assert.ok(
				CANNOT.test(sentence) || !/\b(?:first|second|third) time\b/iu.test(sentence),
				`claims a history that one observation cannot show: ${reply.text}`,
			);
		}
		assertNoInternalTerm(TERMS, reply.text);
	},
);
