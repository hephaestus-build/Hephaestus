import assert from "node:assert/strict";
import test from "node:test";

import { Ajv } from "ajv";

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
	priorPublicFeedback,
	publicObservations,
	readReview,
	reviewToolParameters,
	sameLinesNote,
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
	assert.match(turn, /"id": "current"/u);
	assert.match(turn, /"name": "Describe what changed and why"/u);
	assert.match(turn, /"whyItMatters": "A reviewer needs the reason before the diff\."/u);
	// An undecided practice is a bound by name, without the prose or the lines it looked at.
	assert.match(turn, /Looked at and not decided[^\n]*ships-tests \(NOT_APPLICABLE\)/u);
	assert.ok(!turn.includes("No behaviour changed"), turn);
	assert.match(turn, /did not settle one of its practices: keeps-tests-honest/u);
	assert.match(
		turn,
		/posted as its own comment headed by the file and line, so write every note to stand on its own/u,
	);
	assert.match(turn, /Nothing has been said on this work yet\./u);
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
	assert.deepEqual(priorPublicFeedback(history, work), [
		{
			deliveredAt: "2026-10-05T09:00:00Z",
			body: "Earlier comment on this change.",
			recordedClaimCurrentness: "CURRENT",
			withdrawn: undefined,
		},
	]);
	assert.deepEqual(priorPublicFeedback(history, undefined), []);
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

void test("an empty or partial public review leaves negatives undecided, while all-MET silence is intentional", () => {
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
