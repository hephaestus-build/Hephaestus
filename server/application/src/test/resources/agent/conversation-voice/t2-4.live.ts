import assert from "node:assert/strict";
import test from "node:test";

import {
	ask,
	assertNoInternalTerm,
	type Folder,
	INDEX,
	json,
	type Reply,
	RUNNING,
	sentences,
	skip,
	TERMS,
	TURN_MS,
	turn,
	WORK,
} from "./harness.ts";

const MERGE_READINESS_46 = "inputs/context/merge_readiness/4601.json";

/**
 * Pull request #46, whose approvals do not meet what it requires, as MergeReadinessContentSource lists it. Its detail
 * shows that grace was asked to review it and has not answered.
 */
const WAITING_FOR_APPROVAL: Folder = {
	...RUNNING,
	"INDEX.md": `${INDEX}\n- \`inputs/context/merge_readiness.json\`: their open pull requests, with what the provider says about merging and the reviews by people\n`,
	"inputs/context/merge_readiness.json": json({
		readAt: "Tue 2026-10-27 09:12 +01:00",
		pullRequests: [
			{
				...WORK,
				resource: MERGE_READINESS_46,
				state: "OPEN",
				providerFreshness: "UNKNOWN",
				recordUpdatedAt: "Mon 2026-10-26 23:02 +01:00",
				mergeable: "YES",
				mergeStateStatus: "BLOCKED",
				reviewDecision: "not approved yet",
				checks: "SUCCESS",
				checksFor: "CURRENT_HEAD",
				checksObserved: "STATUS_REPORTED",
				latestReviews: [],
				latestReviewsStatus: "COMPLETE",
				generalNotes: [],
				generalNotesStatus: "COMPLETE",
				threads: [],
			},
		],
		notLoaded: [],
	}),
	[MERGE_READINESS_46]: json({
		...WORK,
		state: "OPEN",
		reviewDecision: "not approved yet",
		requestedReviewers: [{ reviewer: "grace", awaitingVerdict: true }],
		requestedTeams: [],
		latestReviews: [],
		latestReviewsStatus: "COMPLETE",
	}),
};

/** What an answer about #46 waiting for its approval must and must not say. */
function assertWaitsForApproval(reply: Reply) {
	assert.match(reply.text, /#46/u, `does not name the pull request: ${reply.text}`);
	assert.match(
		reply.text,
		/\bnot (?:yet )?(?:been )?approved\b|\bneeds? (?:an? |one more )?approval\b|\bwait(?:s|ing) for (?:an? )?(?:approval|review)\b|\bno approval\b|\bhasn['’]t been approved\b/iu,
		`does not say that #46 waits for an approval: ${reply.text}`,
	);
	for (const sentence of sentences(reply.text)) {
		assert.ok(
			!/\bapproved\b/iu.test(sentence) || /\b(?:not|n['’]t|no|yet|until|before)\b/iu.test(sentence),
			`says that #46 is approved: ${reply.text}`,
		);
	}
	assert.doesNotMatch(
		reply.text,
		/\breadiness\b|\bnot loaded\b|\bcan['’]?t see who\b|\bcannot see who\b/iu,
		`names a document or a limit the detail does not have: ${reply.text}`,
	);
	assert.doesNotMatch(
		reply.text,
		/\b[A-Z]+_[A-Z_]+\b|\b(?:UNKNOWN|BLOCKED|SUCCESS|COMPLETE)\b/u,
		`names a code: ${reply.text}`,
	);
	assertNoInternalTerm(TERMS, reply.text);
}

void test("T2-4: the oracle rejects staging's wording and the opposite answer", () => {
	for (const text of [
		"Merge request #46 shows REVIEW_REQUIRED, but its reviews aren't loaded in my readiness data.",
		"No, pull request #46 is already approved.",
		"Pull request #46 is UNKNOWN.",
	]) {
		assert.throws(() => assertWaitsForApproval({ text, outputTokens: 0, words: 0 }), text);
	}
	assertWaitsForApproval({
		text: "Yes: pull request #46 is not approved yet, and grace hasn't answered the review request.",
		outputTokens: 0,
		words: 0,
	});
});

for (const where of ["app", "Slack"] as const) {
	void test(
		`T2-4 (${where}): Hephaestus says in plain words that a pull request waits for an approval`,
		{ skip, timeout: TURN_MS },
		async () => {
			const reply = await ask(
				`t2-4-waiting-for-review-${where}`,
				turn("Is anything of mine still waiting to be reviewed right now?", true, "", where),
				WAITING_FOR_APPROVAL,
			);
			assert.ok(
				reply.looked.some((entry) => entry.includes("merge_readiness")),
				`answered without reading merge readiness: ${reply.looked.join(", ")}`,
			);
			assertWaitsForApproval(reply);
		},
	);
}
