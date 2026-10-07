import assert from "node:assert/strict";
import test from "node:test";

import { Ajv } from "ajv";

import type { PublicReviewHistory } from "../../../main/resources/agent/pi-review-brief.ts";

import {
	type Channel,
	type ComposedFeedbackEnvelope,
	type ComposedFeedbackUnit,
	REVIEW_LIMITS,
	type ReviewedObservation,
	WITHHOLD_REASONS,
	buildReviewTurn,
	decidedByReview,
	notReachedNote,
	type ComposedReview,
	type PriorAdviceWitness,
	priorAdviceWitnesses,
	priorPublicFeedback,
	publicObservations,
	readReview,
	readSelection,
	reviewToolParameters,
	sameLinesNote,
	selectionMismatch,
	selectionText,
	selectionToolParameters,
	uncertainOutcomes,
	undeliverableUnits,
	validateFeedbackEvidence,
} from "../../../main/resources/agent/pi-runner-composition.ts";

const supersede = (threadKey: string): ComposedFeedbackUnit => ({
	action: "SUPERSEDE",
	channel: "IN_CHAT",
	practiceSlug: "writes-focused-pull-requests",
	supersedesThreadKey: threadKey,
});

const target = (threadKey: string, channel: Channel = "IN_CHAT") => ({
	threadKey,
	channel,
	practiceSlug: "writes-focused-pull-requests",
});

void test("an envelope that lists the threads its units supersede delivers all of them", () => {
	const envelope = {
		preparedTargets: [target("t-1"), target("t-2")],
		units: [supersede("t-1"), supersede("t-2")],
	};

	assert.deepEqual(undeliverableUnits(envelope), []);
});

void test("only the unit naming an unlisted or other-lane thread is undeliverable", () => {
	assert.deepEqual(
		undeliverableUnits({
			preparedTargets: [target("t-1")],
			units: [supersede("t-1"), supersede("t-9")],
		}),
		[supersede("t-9")],
	);
	assert.deepEqual(
		undeliverableUnits({ preparedTargets: [target("t-1", "IN_APP")], units: [supersede("t-1")] }),
		[supersede("t-1")],
	);
	const quiet: ComposedFeedbackEnvelope = {
		preparedTargets: [],
		units: [
			{ action: "WITHHOLD", channel: "IN_CHAT", practiceSlug: "p", withholdReason: "ALREADY_SAID" },
		],
	};
	assert.deepEqual(undeliverableUnits(quiet), []);
	assert.deepEqual(undeliverableUnits(undefined), []);
});

void test("private feedback rests on a problem of its own practice", () => {
	const observations = new Map([
		["confirmed-criteria", { practiceSlug: "acceptance-criteria", outcome: "MET" }],
		["unconfirmed-outcome", { practiceSlug: "issue-outcome", outcome: "NOT_MET" }],
	]);
	const basedOn = ["confirmed-criteria", "unconfirmed-outcome"];

	assert.match(
		validateFeedbackEvidence("acceptance-criteria", basedOn, observations) ?? "",
		/NOT_MET for the primary practice 'acceptance-criteria'/u,
	);
	assert.equal(validateFeedbackEvidence("issue-outcome", basedOn, observations), null);
	assert.match(
		validateFeedbackEvidence("issue-outcome", ["missing"], observations) ?? "",
		/does not name an admitted observation/u,
	);
});

void test("a review names the practices it never settled and forbids a verdict on them", () => {
	assert.equal(notReachedNote([]), "");
	const many = notReachedNote(["ships-tests-with-the-change", "describe-what-and-why"]);
	assert.match(many, /2 of its practices: ships-tests-with-the-change, describe-what-and-why\./u);
	assert.match(many, /Say nothing about them, for or against/u);
});

const cite = (path: string, startLine: number) => ({ path, startLine });

void test("negatives that quote the same line are named as one likely event", () => {
	const note = sameLinesNote([
		{ id: "a", practiceSlug: "scope", outcome: "NOT_MET", citations: [cite("metadata.json", 18)] },
		{
			id: "b",
			practiceSlug: "handoff",
			outcome: "NOT_MET",
			citations: [cite("metadata.json", 18)],
		},
		{ id: "e", practiceSlug: "describe", outcome: "MET", citations: [cite("metadata.json", 18)] },
	]);
	assert.match(note, /- metadata\.json:18: scope \(a\), handoff \(b\)\n/u);
	assert.match(note, /say it once, resting on all of them in basedOn/u);
});

// --- The review on the work -------------------------------------------------------------------

const diffCitation = (line: number) => ({
	index: 0,
	sourceKind: "scm.pull-request.diff",
	path: "App/ContentView.swift",
	side: "NEW",
	startLine: line,
	anchorable: true,
});

const observations = new Map<string, ReviewedObservation>([
	[
		"colors",
		{
			practiceSlug: "uses-adaptive-colors",
			outcome: "NOT_MET",
			citations: [diffCitation(13), { ...diffCitation(16), index: 1 }],
		},
	],
	[
		"handoff",
		{ practiceSlug: "honours-linked-issue-acceptance-criteria", outcome: "NOT_MET", citations: [] },
	],
	["why", { practiceSlug: "describe-what-and-why", outcome: "NOT_MET", citations: [] }],
	["preview", { practiceSlug: "ships-a-preview", outcome: "MET", citations: [] }],
	["unsure", { practiceSlug: "keeps-tests-honest", outcome: "UNDETERMINED", citations: [] }],
	[
		"description",
		{
			practiceSlug: "states-how-to-verify",
			outcome: "NOT_MET",
			citations: [
				{
					index: 0,
					sourceKind: "scm.pull-request.core",
					path: "description.md",
					anchorable: false,
				},
			],
		},
	],
]);

const errorsOf = (value: unknown, lineNotes = true): string[] => {
	const read = readReview(value, observations, lineNotes);
	return "errors" in read ? read.errors : [];
};

void test("a complete summary is stored exactly as written, resting on exactly what it names", () => {
	const body =
		"The description stops at the issue link. Say which of #4's checks this screen covers and why it matters.\n\n" +
		"    let fill = Color(red: 0.2, green: 0.6, blue: 0.3)\n\n" +
		"The preview you added makes the layout easy to check in both appearances; keep it with the next screen.\n";
	const read = readReview(
		{
			summary: { body, basedOn: ["handoff", "why", "preview", "why"] },
			withheld: [{ basedOn: ["colors", "description"], reason: "BELOW_BAR" }],
		},
		observations,
		true,
	);
	assert.ok("review" in read, JSON.stringify(read));
	assert.equal(read.review.summary?.body, body);
	assert.deepEqual(read.review.summary.basedOn, ["handoff", "why", "preview"]);
	assert.deepEqual(read.review.inline, []);
});

void test("a review with only line notes is a whole review: no summary is required", () => {
	const read = readReview(
		{
			inline: [
				{
					body: "This explicit RGB fill stays unchanged in Dark Mode; check contrast in both appearances and use an adaptive fill.",
					basedOn: ["colors"],
					anchor: { observationId: "colors", citationIndex: 0 },
				},
			],
			withheld: [{ basedOn: ["handoff", "why", "description"], reason: "ALREADY_SAID" }],
		},
		observations,
		true,
	);
	assert.ok("review" in read, JSON.stringify(read));
	assert.equal(read.review.summary, null);
	assert.deepEqual(read.review.inline[0]?.anchor, { observationId: "colors", citationIndex: 0 });
	assert.deepEqual(read.review.inline[0].basedOn, ["colors"]);
});

const note = (citationIndex: number) => ({
	body: `The fill on this line fixes one appearance (${citationIndex}).`,
	basedOn: ["colors"],
	anchor: { observationId: "colors", citationIndex },
});

void test("one practice may carry several notes about different lines, never two on one line", () => {
	assert.ok(
		"review" in
			readReview(
				{
					inline: [note(0), note(1)],
					withheld: [{ basedOn: ["handoff", "why", "description"], reason: "BELOW_BAR" }],
				},
				observations,
				true,
			),
	);
	assert.match(
		errorsOf({ inline: [note(0), note(0)] }).join("\n"),
		/inline #2: another note already sits on citation 0 of colors/u,
	);
});

void test("an anchor must be a line of this change cited by an observation the note rests on", () => {
	const errors = errorsOf({
		inline: [
			{ body: "One.", basedOn: ["handoff"], anchor: { observationId: "colors", citationIndex: 0 } },
			{
				body: "Two.",
				basedOn: ["description"],
				anchor: { observationId: "description", citationIndex: 0 },
			},
			{
				body: "Three.",
				basedOn: ["colors"],
				anchor: { observationId: "colors", citationIndex: 7 },
			},
		],
	}).join("\n");
	assert.match(errors, /inline #1: anchor names colors, which this note's basedOn does not/u);
	assert.match(errors, /inline #2: citation 0 of description is not a line of this change/u);
	assert.match(errors, /inline #3: colors has no citation 7/u);
	assert.match(
		errorsOf(
			{
				inline: [
					{ body: "x", basedOn: ["colors"], anchor: { observationId: "colors", citationIndex: 0 } },
				],
			},
			false,
		).join("\n"),
		/this work has no lines to place a note on/u,
	);
});

void test("a part may rest only on admitted observations that decided something", () => {
	const errors = errorsOf({
		summary: { body: "A sentence.", basedOn: ["why", "unsure", "elsewhere"] },
	}).join("\n");
	assert.match(errors, /elsewhere, which is not one of the observations this review may rest on/u);
	assert.match(
		errorsOf({ summary: { body: "A sentence.", basedOn: ["unsure"] } }).join("\n"),
		/decided nothing/u,
	);
	assert.match(
		errorsOf({ summary: { body: "A sentence.", basedOn: [] } }).join("\n"),
		/basedOn is required/u,
	);
});

void test("an observation is either said or withheld, and only a problem can be withheld", () => {
	assert.match(
		errorsOf({
			summary: { body: "A sentence.", basedOn: ["why"] },
			withheld: [{ basedOn: ["why"], reason: "BELOW_BAR" }],
		}).join("\n"),
		/why is both said and withheld/u,
	);
	assert.match(
		errorsOf({ withheld: [{ basedOn: ["preview"], reason: "BELOW_BAR" }] }).join("\n"),
		/only an admitted NOT_MET observation can be withheld/u,
	);
	assert.match(
		errorsOf({ withheld: [{ basedOn: ["why"], reason: "BORED" }] }).join("\n"),
		/reason must be one of NO_MATERIAL_CHANGE, ALREADY_SAID, BELOW_BAR/u,
	);
});

void test("a text is refused for what only the server writes, never for its words", () => {
	assert.ok(
		"review" in
			readReview(
				{
					summary: {
						body: "The acceptance criteria and your own assessment of the empty state are both missing.",
						basedOn: ["handoff"],
					},
					withheld: [{ basedOn: ["colors", "why", "description"], reason: "BELOW_BAR" }],
				},
				observations,
				true,
			),
	);
	assert.match(
		errorsOf({
			summary: { body: "Fine. <!-- hephaestus:practice-review:x -->", basedOn: ["why"] },
		}).join("\n"),
		/may not contain an HTML comment/u,
	);
	assert.match(
		errorsOf({ summary: { body: 'Add the reason.}]"', basedOn: ["why"] } }).join("\n"),
		/left over from a JSON envelope/u,
	);
	assert.match(
		errorsOf({
			summary: { body: "x".repeat(REVIEW_LIMITS.summaryChars + 1), basedOn: ["why"] },
		}).join("\n"),
		/at most 8000 characters/u,
	);
});

void test("a review over the line-note bound is refused whole, not cut", () => {
	const notes = Array.from({ length: REVIEW_LIMITS.inlineNotes + 1 }, () => ({
		body: "x",
		basedOn: ["colors"],
		anchor: { observationId: "colors", citationIndex: 0 },
	}));
	assert.match(
		errorsOf({ inline: notes }).join("\n"),
		/at most 30 line notes; this review has 31/u,
	);
});

void test("what a review decided covers what it says and what it withholds", () => {
	assert.deepEqual(
		[
			...decidedByReview({
				summary: { body: "x", basedOn: ["why"] },
				inline: [
					{ body: "y", basedOn: ["colors"], anchor: { observationId: "colors", citationIndex: 0 } },
				],
				withheld: [{ basedOn: ["handoff"], reason: "ALREADY_SAID" }],
			}),
		].toSorted(),
		["colors", "handoff", "why"],
	);
	assert.equal(decidedByReview(null).size, 0);
});

// --- What the review composition may see --------------------------------------------------------

const PRIVATE_SENTENCE = "Earlier reviews told this person the same thing twice before.";

const admitted = [
	{
		id: "current",
		practiceSlug: "describe-what-and-why",
		outcome: "NOT_MET",
		summary: "The description gives no reason for the screen.",
		evidenceRationale: "The authored description names the screen and nothing about why.",
		publicEligible: true,
		citations: [
			{
				index: 0,
				sourceKind: "scm.pull-request.core",
				path: "description.md",
				quote: "Closes #4",
				verification: { status: "VERIFIED", quoteSha256: "abc" },
				anchorable: false,
			},
		],
	},
	{
		id: "from-history",
		practiceSlug: "defers-review-asks-into-tracked-work",
		outcome: "NOT_MET",
		summary: PRIVATE_SENTENCE,
		evidenceRationale: PRIVATE_SENTENCE,
		publicEligible: false,
		citations: [
			{ index: 0, sourceKind: "hephaestus.feedback-history", path: "history/feedback.json" },
		],
	},
];

void test("the review composition sees only what admission marked eligible, and that whole", () => {
	const shown = publicObservations(admitted);
	assert.deepEqual(
		shown.map((observation) => observation.id),
		["current"],
	);
	assert.equal(
		shown[0]?.evidenceRationale,
		"The authored description names the screen and nothing about why.",
	);
	assert.deepEqual(shown[0].citations, [
		{
			index: 0,
			sourceKind: "scm.pull-request.core",
			path: "description.md",
			quote: "Closes #4",
			anchorable: false,
		},
	]);
	const turn = buildReviewTurn({
		sameWork:
			"A scm.pull_request, captured at 2026-10-01T10:00:00Z. Its title: Add the login screen.",
		observations: shown,
		undecided: uncertainOutcomes([
			...admitted,
			{
				id: "na",
				practiceSlug: "ships-tests",
				outcome: "NOT_APPLICABLE",
				summary: "No behaviour changed, so no test is owed.",
				citations: [{ index: 0, sourceKind: "scm.pull-request.diff", quote: "+ text" }],
			},
		]),
		alreadySaid: [],
		captured: {
			capturedAt: null,
			recipient: { author: null, authorId: null },
			sources: [],
			statements: [],
		},
		practices: [
			{
				slug: "describe-what-and-why",
				name: "Describe what changed and why",
				whyItMatters: "A reviewer needs the reason before the diff.",
				knownLimitations: ["The review cannot see conversations outside the change."],
			},
		],
		notReached: ["keeps-tests-honest"],
		lineNotes: true,
	});
	assert.ok(!turn.includes(PRIVATE_SENTENCE), turn);
	assert.ok(!turn.includes("from-history"), turn);
	// The work is named before anything the review may rest on.
	const record = turn.indexOf("Add the login screen.");
	assert.ok(record !== -1 && record < turn.indexOf('"id": "current"'), turn);
	assert.match(turn, /"id": "current"/u);
	assert.match(turn, /"name": "Describe what changed and why"/u);
	assert.match(turn, /"whyItMatters": "A reviewer needs the reason before the diff\."/u);
	// An undecided practice is a bound by name, without the prose or the lines it looked at.
	assert.match(turn, /Looked at and not decided[^\n]*ships-tests \(NOT_APPLICABLE\)/u);
	assert.ok(!turn.includes("No behaviour changed"), turn);
	assert.match(turn, /did not settle one of its practices: keeps-tests-honest/u);
	assert.match(turn, /No same-work delivered feedback is shown here\./u);
	// A discussion this run did not capture is unknown, never an empty one.
	assert.match(turn, /was not part of this capture, so it is unknown/u);
});

void test("the public turn carries captured discussion once, with its locator and omission qualifications", () => {
	const captured = {
		capturedAt: "2026-10-06T09:00:00Z",
		recipient: { author: "developer", authorId: "10" },
		sources: [
			{
				kind: "scm.issue.comments",
				path: "context/comments.json",
				availability: "AVAILABLE",
				content: "NONEMPTY",
				completeness: "PARTIAL",
				limitations: ["Older comments are outside the captured window."],
				omitted: null,
				qualifications: [],
			},
		],
		statements: [
			{
				witnessId: "comment:context/comments.json:7",
				sourcePath: "context/comments.json",
				sourceKind: "scm.issue.comments",
				nativeId: "7",
				author: "reviewer",
				authorId: "11",
				origin: "UNKNOWN",
				body: "Please describe how to try the screen.",
				statedAt: "2026-10-06T08:00:00Z",
				updatedAt: null,
				reviewedRevision: null,
				eligibleForPriorAdvice: true,
				state: null,
				dismissed: null,
				outdated: null,
			},
		],
	} satisfies PublicReviewHistory;
	const turn = buildReviewTurn({
		sameWork: "The captured issue asks for a login screen.",
		observations: [],
		undecided: [],
		alreadySaid: [],
		captured,
		practices: [],
		notReached: [],
		lineNotes: false,
	});
	assert.equal(turn.split('"path": "context/comments.json"').length - 1, 1);
	assert.equal(turn.split("Please describe how to try the screen.").length - 1, 1);
	assert.ok(turn.includes('"witnessId": "comment:context/comments.json:7"'));
	assert.ok(turn.includes("Older comments are outside the captured window."));
	const omitted = "Captured discussion and its omission index exceed the size bound.";
	const withoutBodies = buildReviewTurn({
		sameWork: "The captured issue asks for a login screen.",
		observations: [],
		undecided: [],
		alreadySaid: [],
		captured: { ...captured, sources: [], statements: [], omitted },
		practices: [],
		notReached: [],
		lineNotes: false,
	});
	assert.ok(withoutBodies.includes(omitted));
});

void test("an observation admission did not mark, or one that decided nothing, is left out", () => {
	assert.deepEqual(publicObservations([{ id: "unmarked", outcome: "NOT_MET", citations: [] }]), []);
	assert.deepEqual(
		publicObservations([
			{ id: "unsure", outcome: "UNDETERMINED", publicEligible: true, citations: [] },
		]),
		[],
	);
});

void test("a text resting on anything but observation ids is refused whole, never filtered", () => {
	assert.match(
		errorsOf({ summary: { body: "A sentence.", basedOn: ["why", 42] } }).join("\n"),
		/basedOn must be an array of observation id strings/u,
	);
	assert.match(
		errorsOf({ summary: { body: "A sentence.", basedOn: "why" } }).join("\n"),
		/basedOn must be an array of observation id strings/u,
	);
	assert.match(
		errorsOf({ withheld: [{ basedOn: ["why", null], reason: "BELOW_BAR" }] }).join("\n"),
		/withheld #1: basedOn must be an array/u,
	);
});

void test("only what was said on this same work, on the work, is supplied as already said", () => {
	const work = "scm.pull_request:https://gitlab.example/group/repo/-/merge_requests/3";
	const history = {
		feedback: [
			{
				channel: "IN_CONTEXT",
				artifact: {
					kind: "scm.pull_request",
					url: "https://gitlab.example/group/repo/-/merge_requests/3",
				},
				deliveredAt: "2026-10-05T09:00:00Z",
				body: "Earlier comment on this change.",
				recordedClaimCurrentness: "CURRENT",
			},
			{
				channel: "IN_APP",
				artifact: {
					kind: "scm.pull_request",
					url: "https://gitlab.example/group/repo/-/merge_requests/3",
				},
				body: "A card on their practice page.",
			},
			{
				channel: "IN_CONTEXT",
				artifact: {
					kind: "scm.pull_request",
					url: "https://gitlab.example/group/repo/-/merge_requests/1",
				},
				body: "A comment on other work.",
			},
		],
	};
	assert.deepEqual(priorPublicFeedback(history, work, "2026-10-06T09:00:00Z").feedback, [
		{
			// The history names no id for it: shown as context, never as advice this work received.
			witnessId: null,
			deliveredAt: "2026-10-05T09:00:00Z",
			body: "Earlier comment on this change.",
			recordedClaimCurrentness: "CURRENT",
			withdrawn: undefined,
			eligibleForPriorAdvice: false,
		},
	]);
	assert.deepEqual(priorPublicFeedback(history, undefined, "2026-10-06T09:00:00Z").feedback, []);
});

const historyFeedbackId = (n: number) => `00000000-0000-4000-8000-00000000000${n}`;

void test("own feedback on this work stands as prior advice only when named, current, shown and delivered before the capture", () => {
	const work = "scm.pull_request:https://gitlab.example/group/repo/-/merge_requests/3";
	const artifact = {
		kind: "scm.pull_request",
		url: "https://gitlab.example/group/repo/-/merge_requests/3",
	};
	const entry = (n: number, overrides: Record<string, unknown> = {}) => ({
		channel: "IN_CONTEXT",
		artifact,
		id: historyFeedbackId(n),
		reviewedRevision: "a".repeat(40),
		basedOn: [{ observationId: `earlier-${n}` }],
		deliveredAt: "2026-10-05T09:00:00Z",
		body: `Delivered comment ${n}.`,
		recordedClaimCurrentness: "CURRENT",
		...overrides,
	});
	const history = {
		feedback: [
			entry(1),
			entry(2, { deliveredAt: "2026-10-07T09:00:00Z" }),
			entry(3, { recordedClaimCurrentness: "STALE", body: undefined }),
			entry(4, { withdrawn: true, body: undefined }),
			entry(5, { deliveredAt: undefined }),
		],
	};
	const said = priorPublicFeedback(history, work, "2026-10-06T09:00:00Z").feedback;
	assert.deepEqual(
		said.map((statement) => [statement.witnessId, statement.eligibleForPriorAdvice]),
		[
			[`feedback:${historyFeedbackId(1)}`, true],
			[`feedback:${historyFeedbackId(2)}`, false],
			[`feedback:${historyFeedbackId(3)}`, false],
			[`feedback:${historyFeedbackId(4)}`, false],
			[`feedback:${historyFeedbackId(5)}`, false],
		],
	);
	// What the history recorded about the feedback stays as it was staged.
	const original = said[0];
	assert.ok(original);
	assert.equal(original.reviewedRevision, "a".repeat(40));
	assert.deepEqual(original.basedOn, [{ observationId: "earlier-1" }]);
	// Without a known capture time nothing can be placed before it.
	assert.ok(
		priorPublicFeedback(history, work, null).feedback.every(
			(statement) => !statement.eligibleForPriorAdvice,
		),
	);
	const witnesses = priorAdviceWitnesses(said, [
		{
			witnessId: "github:review-comment:7",
			sourcePath: "context/review_threads.json",
			sourceKind: "scm.pull-request.review-threads",
			nativeId: "7",
			author: "tutor",
			authorId: "11",
			origin: "UNKNOWN",
			body: "Please describe how to try this.",
			statedAt: "2026-10-05T08:00:00Z",
			updatedAt: null,
			reviewedRevision: null,
			eligibleForPriorAdvice: true,
			state: null,
			dismissed: null,
			outdated: null,
		},
	]);
	assert.deepEqual(witnesses.get(`feedback:${historyFeedbackId(1)}`), {
		eligibleForPriorAdvice: true,
	});
	assert.deepEqual(witnesses.get(`feedback:${historyFeedbackId(4)}`), {
		eligibleForPriorAdvice: false,
	});
	assert.deepEqual(witnesses.get("github:review-comment:7"), { eligibleForPriorAdvice: true });
});

void test("own history omits whole oversized or over-budget entries without hiding later usable advice", () => {
	const artifact = {
		kind: "scm.pull_request",
		url: "https://gitlab.example/group/repo/-/merge_requests/3",
	};
	const entry = (n: number, body: string) => ({
		channel: "IN_CONTEXT",
		artifact,
		id: historyFeedbackId(n),
		body,
		deliveredAt: "2026-10-05T09:00:00Z",
		recordedClaimCurrentness: "CURRENT",
	});
	const history = {
		feedback: [
			entry(1, "oversized ".repeat(300)),
			entry(2, "Useful earlier advice."),
			entry(3, "Long earlier advice. ".repeat(35)),
			entry(4, "Later usable advice."),
		],
	};
	const limits = { sourceChars: 1200, totalChars: 1300 };
	const view = priorPublicFeedback(
		history,
		"scm.pull_request:https://gitlab.example/group/repo/-/merge_requests/3",
		"2026-10-06T09:00:00Z",
		limits,
	);
	assert.deepEqual(
		view.feedback.map((row) => row.id),
		[historyFeedbackId(2), historyFeedbackId(4)],
	);
	assert.deepEqual(view.omissions, { oversizedEntries: 1, budgetEntries: 1 });
	const witnesses = priorAdviceWitnesses(view.feedback, []);
	assert.ok(!witnesses.has(`feedback:${historyFeedbackId(1)}`));
	assert.ok(!witnesses.has(`feedback:${historyFeedbackId(3)}`));
	assert.equal(witnesses.get(`feedback:${historyFeedbackId(4)}`)?.eligibleForPriorAdvice, true);
	const input = {
		sameWork: "The captured work.",
		observations: [],
		undecided: [],
		alreadySaid: view.feedback,
		captured: {
			capturedAt: null,
			recipient: { author: null, authorId: null },
			sources: [],
			statements: [],
		},
		practices: [],
		notReached: [],
		lineNotes: false,
	};
	const turn = buildReviewTurn({ ...input, ownHistoryOmissions: view.omissions });
	const own = turn
		.split("### Already said on this work\n")[1]
		?.split("What people and tools said")[0];
	assert.ok(typeof own === "string");
	assert.ok(own.length <= limits.totalChars);
	assert.match(own, /1 entries exceed the per-entry limit/u);
	assert.match(own, /1 exceed the total history limit/u);
	assert.ok(!turn.includes(historyFeedbackId(1)));
	assert.ok(!turn.includes(historyFeedbackId(3)));
	assert.ok(!turn.includes("oversized"));
	assert.equal(
		buildReviewTurn(input),
		buildReviewTurn({ ...input, ownHistoryOmissions: { oversizedEntries: 0, budgetEntries: 0 } }),
	);
});

// --- The report_review schema of one run ---------------------------------------------------------

const decidedOnly = new Map([...observations].filter(([id]) => id !== "unsure"));

const validatorFor = (reviewable: ReadonlyMap<string, ReviewedObservation>, lineNotes = true) =>
	new Ajv({ strict: true, allErrors: true }).compile(reviewToolParameters(reviewable, lineNotes));

void test("the schema of a run requires each part's fields, names only its ids, and leaves an empty review valid", () => {
	const valid = validatorFor(decidedOnly);
	assert.ok(valid({}), "an empty review is a decision");
	assert.ok(
		valid({
			summary: { body: "Say why.", basedOn: ["why", "preview"] },
			inline: [
				{
					body: "Adapt.",
					basedOn: ["colors"],
					anchor: { observationId: "colors", citationIndex: 1 },
				},
			],
			withheld: [{ basedOn: ["handoff"], reason: "ALREADY_SAID" }],
		}),
		JSON.stringify(valid.errors),
	);
	// The three omissions a real composer made, each refused by the schema before readReview sees it.
	assert.ok(!valid({ summary: { body: "Say why." } }));
	assert.ok(!valid({ inline: [{ body: "Adapt.", basedOn: ["colors"] }] }));
	assert.ok(!valid({ withheld: [{ basedOn: ["handoff"] }] }));
	assert.ok(!valid({ summary: { body: "Say why.", basedOn: [] } }));
	assert.ok(!valid({ summary: { body: "Say why.", basedOn: ["why", 42] } }));
	assert.ok(!valid({ summary: { body: "Say why.", basedOn: ["elsewhere"] } }));
	assert.ok(
		!valid({ withheld: [{ basedOn: ["preview"], reason: "BELOW_BAR" }] }),
		"a MET cannot be withheld",
	);
	assert.ok(
		!valid({
			inline: [{ body: "x", basedOn: ["why"], anchor: { observationId: "why", citationIndex: 0 } }],
		}),
		"no line of this change is cited by why",
	);
	assert.ok(
		!valid({ summary: { body: "x".repeat(REVIEW_LIMITS.summaryChars + 1), basedOn: ["why"] } }),
	);
	const tooMany = Array.from({ length: REVIEW_LIMITS.inlineNotes + 1 }, () => ({
		body: "x",
		basedOn: ["colors"],
		anchor: { observationId: "colors", citationIndex: 0 },
	}));
	assert.ok(!valid({ inline: tooMany }));
});

void test("a run with nothing to withhold or no line to sit on takes no such items, with a valid schema", () => {
	const metOnly = new Map([...observations].filter(([id]) => id === "preview"));
	const valid = validatorFor(metOnly);
	assert.ok(valid({ summary: { body: "The preview helps.", basedOn: ["preview"] } }));
	assert.ok(!valid({ withheld: [{ basedOn: ["preview"], reason: "BELOW_BAR" }] }));
	assert.ok(
		!valid({
			inline: [
				{ body: "x", basedOn: ["preview"], anchor: { observationId: "preview", citationIndex: 0 } },
			],
		}),
	);
	const issue = validatorFor(decidedOnly, false);
	assert.ok(
		!issue({
			inline: [
				{ body: "x", basedOn: ["colors"], anchor: { observationId: "colors", citationIndex: 0 } },
			],
		}),
	);
	assert.deepEqual(
		reviewToolParameters(decidedOnly, true).properties.withheld.items.properties.reason.enum,
		[...WITHHOLD_REASONS],
	);
	assert.throws(() =>
		reviewToolParameters(new Map([...observations].filter(([id]) => id === "unsure")), true),
	);
});

void test("an empty or partial public review leaves negatives undecided, while an empty all-MET review is a valid decision", () => {
	const negative = new Map<string, ReviewedObservation>([
		["one", { practiceSlug: "one", outcome: "NOT_MET", citations: [] }],
		["two", { practiceSlug: "two", outcome: "NOT_MET", citations: [] }],
	]);
	const empty = readReview({}, negative, false);
	assert.ok("errors" in empty);
	assert.match(empty.errors.join("\n"), /one, two has no decision/u);
	const partial = readReview(
		{ summary: { body: "The first description needs its purpose.", basedOn: ["one"] } },
		negative,
		false,
	);
	assert.ok("errors" in partial);
	assert.match(partial.errors.join("\n"), /two has no decision/u);
	assert.deepEqual(
		readReview({}, new Map([["met", { practiceSlug: "p", outcome: "MET", citations: [] }]]), false),
		{
			review: { summary: null, inline: [], withheld: [] },
		},
	);
});

void test("summary-only support refuses a whole inline body while allowing its summary", () => {
	const support = new Map<string, ReviewedObservation>([
		[
			"summary",
			{ practiceSlug: "description", outcome: "NOT_MET", citations: [], summaryOnly: true },
		],
		["line", { practiceSlug: "code", outcome: "NOT_MET", citations: [{ anchorable: true }] }],
	]);
	assert.ok(
		"errors" in
			readReview(
				{
					inline: [
						{
							body: "Both concerns.",
							basedOn: ["summary", "line"],
							anchor: { observationId: "line", citationIndex: 0 },
						},
					],
				},
				support,
				true,
			),
	);
	assert.ok(
		"review" in
			readReview(
				{ summary: { body: "Both concerns.", basedOn: ["summary", "line"] } },
				support,
				true,
			),
	);
});

// --- The selection before the review ------------------------------------------------------------

/** A tutor's comment and Hephaestus's own delivered comment may stand as prior advice; the author's own may not. */
const witnesses = new Map<string, PriorAdviceWitness>([
	["github:review-comment:7", { eligibleForPriorAdvice: true }],
	["feedback:00000000-0000-4000-8000-000000000001", { eligibleForPriorAdvice: true }],
	["github:issue-comment:9", { eligibleForPriorAdvice: false }],
]);

const selectionErrors = (value: unknown): string[] => {
	const read = readSelection(value, observations, witnesses);
	return "errors" in read ? read.errors : [];
};

void test("a selection decides every NOT_MET observation once, and only from what the review may rest on", () => {
	assert.deepEqual(
		readSelection(
			{
				selected: ["colors", "why"],
				withheld: [{ basedOn: ["handoff", "description"], reason: "BELOW_BAR" }],
			},
			observations,
			witnesses,
		),
		{
			selection: {
				selected: ["colors", "why"],
				withheld: [{ basedOn: ["handoff", "description"], reason: "BELOW_BAR" }],
			},
		},
	);
	const missing = selectionErrors({ selected: ["colors"] }).join("\n");
	assert.match(missing, /handoff, why, description has no decision/u);
	assert.match(
		selectionErrors({
			selected: ["colors", "why", "handoff"],
			withheld: [{ basedOn: ["why", "description"], reason: "BELOW_BAR" }],
		}).join("\n"),
		/why is both selected and withheld/u,
	);
	assert.match(
		selectionErrors({
			selected: ["colors", "colors", "why", "handoff"],
			withheld: [
				{ basedOn: ["description"], reason: "BELOW_BAR" },
				{ basedOn: ["description"], reason: "BELOW_BAR" },
			],
		}).join("\n"),
		/selected names colors more than once[\s\S]*description is withheld more than once/u,
	);
	const strays = selectionErrors({
		selected: ["colors", "why", "handoff", "description", "elsewhere", "unsure"],
	}).join("\n");
	assert.match(strays, /selected names elsewhere, which is not one of the observations/u);
	assert.match(strays, /selected names unsure, which decided nothing/u);
	assert.match(
		selectionErrors({
			selected: ["colors", "why", "handoff", "description"],
			withheld: [{ basedOn: ["preview"], reason: "BELOW_BAR" }],
		}).join("\n"),
		/only an admitted NOT_MET observation can be withheld/u,
	);
	assert.match(selectionErrors({ selected: "colors" }).join("\n"), /selected must be an array/u);
	// A withholding container that is not a list is refused, never read as one decision or as none.
	for (const container of [{ basedOn: ["handoff"], reason: "BELOW_BAR" }, "BELOW_BAR", 3]) {
		assert.match(
			selectionErrors({ selected: ["colors", "why", "description"], withheld: container }).join(
				"\n",
			),
			/withheld must be an array of withholding decisions/u,
		);
	}
	// An absent or null container means nothing is withheld.
	assert.ok(
		"selection" in
			readSelection(
				{ selected: ["colors", "why", "handoff", "description"], withheld: null },
				observations,
				witnesses,
			),
	);
	assert.match(selectionErrors({ chosen: [] }).join("\n"), /unknown selection field\(s\): chosen/u);
});

void test("a MET observation is selected only for an acknowledgement worth making, and all-MET work may stay quiet", () => {
	const metOnly = new Map([...observations].filter(([id]) => id === "preview"));
	assert.deepEqual(readSelection({}, metOnly, witnesses), {
		selection: { selected: [], withheld: [] },
	});
	assert.deepEqual(readSelection({ selected: ["preview"] }, metOnly, witnesses), {
		selection: { selected: ["preview"], withheld: [] },
	});
	// With problems on the work, a MET observation is still optional.
	assert.ok(
		"selection" in
			readSelection(
				{ selected: ["colors", "why", "handoff", "description"] },
				observations,
				witnesses,
			),
	);
});

const selectedWithheld = (withheld: unknown) => ({
	selected: ["colors", "why", "description"],
	withheld: [withheld],
});

void test("a withholding that says the advice was given names a statement that may stand as that advice", () => {
	for (const reason of ["ALREADY_SAID", "NO_MATERIAL_CHANGE"]) {
		assert.match(
			selectionErrors(selectedWithheld({ basedOn: ["handoff"], reason })).join("\n"),
			new RegExp(`withheld #1: ${reason} names in witnessIds`, "u"),
		);
	}
	assert.match(
		selectionErrors(
			selectedWithheld({
				basedOn: ["handoff"],
				reason: "ALREADY_SAID",
				witnessIds: ["github:issue-comment:9"],
			}),
		).join("\n"),
		/github:issue-comment:9, which is shown as context but cannot stand as advice/u,
	);
	assert.match(
		selectionErrors(
			selectedWithheld({
				basedOn: ["handoff"],
				reason: "ALREADY_SAID",
				witnessIds: ["github:review-comment:8"],
			}),
		).join("\n"),
		/github:review-comment:8, which is not a statement shown under what was already said/u,
	);
	// A human reviewer's request and Hephaestus's own delivered comment can each stand as the advice.
	for (const witness of [
		"github:review-comment:7",
		"feedback:00000000-0000-4000-8000-000000000001",
	]) {
		assert.deepEqual(
			readSelection(
				selectedWithheld({
					basedOn: ["handoff"],
					reason: "NO_MATERIAL_CHANGE",
					witnessIds: [witness],
				}),
				observations,
				witnesses,
			),
			{
				selection: {
					selected: ["colors", "why", "description"],
					withheld: [{ basedOn: ["handoff"], reason: "NO_MATERIAL_CHANGE", witnessIds: [witness] }],
				},
			},
		);
	}
	// Below the bar is a judgement about this reader, not a claim about what was said before.
	assert.ok(
		"selection" in
			readSelection(
				selectedWithheld({ basedOn: ["handoff"], reason: "BELOW_BAR" }),
				observations,
				witnesses,
			),
	);
});

const selectedReview = (overrides: Partial<ComposedReview>): ComposedReview => ({
	summary: { body: "Say why, and keep the preview.", basedOn: ["why", "preview"] },
	inline: [
		{
			body: "Use an adaptive color here.",
			basedOn: ["colors"],
			anchor: { observationId: "colors", citationIndex: 0 },
		},
	],
	withheld: [{ basedOn: ["handoff", "description"], reason: "BELOW_BAR" }],
	...overrides,
});

void test("a final review rests on exactly the accepted selection, positives and withholdings included", () => {
	const selection = {
		selected: ["colors", "why", "preview"],
		withheld: [{ basedOn: ["handoff", "description"], reason: "BELOW_BAR" as const }],
	};
	assert.deepEqual(selectionMismatch(selectedReview({}), selection), []);
	// Withholdings may be grouped differently; each observation keeps its accepted reason.
	assert.deepEqual(
		selectionMismatch(
			selectedReview({
				withheld: [
					{ basedOn: ["description"], reason: "BELOW_BAR" },
					{ basedOn: ["handoff"], reason: "BELOW_BAR" },
				],
			}),
			selection,
		),
		[],
	);
	assert.match(
		selectionMismatch(
			selectedReview({ summary: { body: "Say why.", basedOn: ["why"] } }),
			selection,
		).join("\n"),
		/selects preview, which no text speaks about/u,
	);
	assert.match(
		selectionMismatch(
			selectedReview({
				summary: {
					body: "Say why, keep the preview, and link the issue.",
					basedOn: ["why", "preview", "handoff"],
				},
				withheld: [{ basedOn: ["description"], reason: "BELOW_BAR" }],
			}),
			selection,
		).join("\n"),
		/speaks about handoff, which the accepted selection does not select[\s\S]*withheld differs from the accepted selection for handoff/u,
	);
	assert.match(
		selectionMismatch(
			selectedReview({
				withheld: [{ basedOn: ["handoff", "description"], reason: "ALREADY_SAID" }],
			}),
			selection,
		).join("\n"),
		/withheld differs from the accepted selection for handoff, description/u,
	);
});

const selectionValidatorFor = (eligible: readonly string[], reviewable = decidedOnly) =>
	new Ajv({ strict: true, allErrors: true }).compile(selectionToolParameters(reviewable, eligible));

void test("the selection schema offers this run's decided ids and only the witnesses that may stand as prior advice", () => {
	const withWitness = selectionValidatorFor(["github:review-comment:7"]);
	assert.ok(
		withWitness({}),
		"an empty selection is valid in shape; readSelection decides coverage",
	);
	assert.ok(
		withWitness({
			selected: ["colors", "preview"],
			withheld: [
				{ basedOn: ["handoff"], reason: "ALREADY_SAID", witnessIds: ["github:review-comment:7"] },
			],
		}),
		JSON.stringify(withWitness.errors),
	);
	assert.ok(!withWitness({ selected: ["unsure"] }));
	assert.ok(!withWitness({ withheld: [{ basedOn: ["preview"], reason: "BELOW_BAR" }] }));
	assert.ok(
		!withWitness({
			withheld: [
				{ basedOn: ["handoff"], reason: "ALREADY_SAID", witnessIds: ["github:issue-comment:9"] },
			],
		}),
	);
	const noWitness = selectionValidatorFor([]);
	assert.ok(noWitness({ withheld: [{ basedOn: ["handoff"], reason: "BELOW_BAR" }] }));
	assert.ok(
		!noWitness({ withheld: [{ basedOn: ["handoff"], reason: "ALREADY_SAID", witnessIds: ["x"] }] }),
	);
	const metOnly = selectionValidatorFor(
		[],
		new Map([...observations].filter(([id]) => id === "preview")),
	);
	assert.ok(metOnly({ selected: ["preview"] }));
	assert.ok(!metOnly({ withheld: [{ basedOn: ["preview"], reason: "BELOW_BAR" }] }));
	assert.throws(() =>
		selectionToolParameters(new Map([...observations].filter(([id]) => id === "unsure")), []),
	);
});

void test("an accepted selection is shown with the admitted public rows it selected, whole, and no others", () => {
	const qualification =
		`The captured diff shows the label is set in code only; whether a screen reader announces it was not run, and the second view's preview was not part of this capture. `.repeat(
			4,
		);
	const reviewable = publicObservations([
		{
			id: "spoken",
			practiceSlug: "makes-ui-accessible",
			outcome: "NOT_MET",
			publicEligible: true,
			summary: "The save button has no accessible label",
			evidenceRationale: qualification,
			citations: [
				{
					index: 0,
					path: "App/Editor.swift",
					startLine: 24,
					quote: 'Button(action: save) { Image(systemName: "checkmark") }',
					verification: { status: "VERIFIED" },
				},
			],
		},
		{
			id: "held",
			practiceSlug: "describe-what-and-why",
			outcome: "NOT_MET",
			publicEligible: true,
			summary: "The description gives no reason",
			citations: [],
		},
		{
			id: "routine",
			practiceSlug: "ships-a-preview",
			outcome: "MET",
			publicEligible: true,
			citations: [],
		},
		{
			id: "private",
			practiceSlug: "makes-ui-accessible",
			outcome: "NOT_MET",
			publicEligible: false,
			summary: "Told twice before",
			citations: [],
		},
	]);
	const selection = {
		selected: ["spoken", "private"],
		withheld: [{ basedOn: ["held"], reason: "BELOW_BAR" as const }],
	};
	const text = selectionText(selection, reviewable, () => null);
	const shown: unknown = JSON.parse(text.slice(text.indexOf("{"), text.lastIndexOf("}") + 1));
	// The selected row exactly as admission's public view carries it: its rationale and citation unchanged.
	assert.deepEqual(shown, {
		acceptedSelection: selection,
		selectedObservations: [reviewable.find((row) => row.id === "spoken")],
	});
	assert.ok(text.includes(JSON.stringify(qualification)), text);
	assert.ok(!text.includes("Told twice before"), text);
	assert.ok(!text.includes("The description gives no reason"), text);
	assert.ok(!text.includes('"verification"'), text);
});

const ENGAGEMENT_CRITERIA =
	"## The standard\nA review request is answered by a fix, a reasoned decline, or a clarification.\n\n" +
	"## Judge\n- MET: each request has one of those responses.\n- NOT_MET: a request has none.\n\n" +
	"```swift\n// a staged example stays inside its own fence\n```";

void test("an accepted selection carries the whole staged criteria of the practices it selected, once each", () => {
	const reviewable = publicObservations([
		{
			id: "unanswered",
			practiceSlug: "engages-with-review",
			outcome: "NOT_MET",
			publicEligible: true,
			summary: "A design request has no reply",
			citations: [],
		},
		{
			id: "unanswered-again",
			practiceSlug: "engages-with-review",
			outcome: "NOT_MET",
			publicEligible: true,
			summary: "A second request has no reply",
			citations: [],
		},
		{
			id: "held",
			practiceSlug: "describe-what-and-why",
			outcome: "NOT_MET",
			publicEligible: true,
			summary: "The description gives no reason",
			citations: [],
		},
		{
			id: "routine",
			practiceSlug: "ships-a-preview",
			outcome: "MET",
			publicEligible: true,
			citations: [],
		},
	]);
	const read: string[] = [];
	const staged = (slug: string) => {
		read.push(slug);
		return slug === "engages-with-review"
			? ENGAGEMENT_CRITERIA
			: `Criteria of ${slug} must not travel.`;
	};

	const text = selectionText(
		{
			selected: ["unanswered", "unanswered-again"],
			withheld: [{ basedOn: ["held"], reason: "BELOW_BAR" }],
		},
		reviewable,
		staged,
	);

	// Exactly the selected practice's criteria, read once, whole and unchanged, alternatives included.
	assert.deepEqual(read, ["engages-with-review"]);
	assert.ok(text.includes(`\`\`\`\`markdown\n${ENGAGEMENT_CRITERIA}\n\`\`\`\``), text);
	assert.equal(text.split("### Criteria of `engages-with-review`").length, 2, text);
	assert.ok(!text.includes("must not travel"), text);
});

void test("a selection with nothing selected carries no criteria, and an unavailable one says so", () => {
	const reviewable = publicObservations([
		{
			id: "held",
			practiceSlug: "describe-what-and-why",
			outcome: "NOT_MET",
			publicEligible: true,
			summary: "The description gives no reason",
			citations: [],
		},
	]);
	const quiet = selectionText(
		{ selected: [], withheld: [{ basedOn: ["held"], reason: "BELOW_BAR" }] },
		reviewable,
		() => {
			throw new Error("an empty selection reads no criteria");
		},
	);
	assert.ok(!quiet.includes("### Criteria of"), quiet);

	const missing = selectionText({ selected: ["held"], withheld: [] }, reviewable, () => null);
	assert.ok(
		missing.includes("### Criteria of `describe-what-and-why` — not available for this review"),
		missing,
	);
});
