import assert from "node:assert/strict";
import test from "node:test";

import { Ajv } from "ajv";

import type { PublicReviewHistory } from "../../../main/resources/agent/pi-review-brief.ts";

import {
	type Channel,
	type ComposedFeedbackEnvelope,
	type ComposedFeedbackUnit,
	REVIEW_LIMITS,
	DISPOSITIONS,
	consultedStandard,
	type ReviewedObservation,
	STANDARD_REFERENCE,
	WRITE_CONTRACT,
	buildReviewTurn,
	decidedByReview,
	notReachedNote,
	type PriorAdviceWitness,
	priorAdviceWitnesses,
	priorPublicFeedback,
	publicObservations,
	type ReviewContext,
	practiceStandard,
	readReview,
	readablePractices,
	reviewToolParameters,
	sameLinesNote,
	notMetReference,
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

/** A tutor's comment and Hephaestus's own delivered comment may stand as prior advice; the author's own may not. */
const witnesses = new Map<string, PriorAdviceWitness>([
	["github:review-comment:7", { eligibleForPriorAdvice: true, eligibleForAlreadySaid: true }],
	[
		"feedback:00000000-0000-4000-8000-000000000001",
		{ eligibleForPriorAdvice: true, eligibleForAlreadySaid: true },
	],
	["github:issue-comment:9", { eligibleForPriorAdvice: false, eligibleForAlreadySaid: false }],
]);

/** Every standard in view, for the rules that are not about which standard the review was written with. */
const inView: ReviewContext = {
	witnesses,
	standardsInView: new Set(
		[...observations.values()].map((observation) => observation.practiceSlug),
	),
};

const raise = (observationId: string) => ({ observationId, disposition: "RAISE" });
const below = (observationId: string) => ({ observationId, disposition: "BELOW_BAR" });
const allRaised = [raise("colors"), raise("handoff"), raise("why"), raise("description")];

const errorsOf = (value: unknown, lineNotes = true, context = inView): string[] => {
	const read = readReview(value, observations, lineNotes, context);
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
			decisions: [raise("handoff"), raise("why"), below("colors"), below("description")],
		},
		observations,
		true,
		inView,
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
			decisions: [
				raise("colors"),
				...["handoff", "why", "description"].map((observationId) => ({
					observationId,
					disposition: "ALREADY_SAID",
					witnessIds: ["github:review-comment:7"],
				})),
			],
		},
		observations,
		true,
		inView,
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
					decisions: [raise("colors"), below("handoff"), below("why"), below("description")],
				},
				observations,
				true,
				inView,
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

void test("an observation is raised or withheld, and only a problem takes a decision", () => {
	assert.match(
		errorsOf({
			decisions: [below("why")],
			summary: { body: "A sentence.", basedOn: ["why"] },
		}).join("\n"),
		/why is withheld as BELOW_BAR, and a text speaks about it/u,
	);
	assert.match(
		errorsOf({ decisions: [below("preview")] }).join("\n"),
		/preview is not a NOT_MET observation/u,
	);
	assert.match(
		errorsOf({ decisions: [{ observationId: "why", disposition: "BORED" }] }).join("\n"),
		/disposition must be one of RAISE, NO_MATERIAL_CHANGE, ALREADY_SAID, BELOW_BAR/u,
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
					decisions: [raise("handoff"), below("colors"), below("why"), below("description")],
				},
				observations,
				true,
				inView,
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
	const read: string[] = [];
	const describeCriteria = "## The standard\nThe description says why the change is made.\n";
	const turn = buildReviewTurn({
		sameWork:
			"A scm.pull_request, captured at 2026-10-01T10:00:00Z. Its title: Add the login screen.",
		observations: [
			...shown,
			{
				id: "named",
				practiceSlug: "names-the-screen",
				outcome: "MET",
				publicEligible: true,
				citations: [],
			},
		],
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
		stagedCriteria: (slug) => {
			read.push(slug);
			return describeCriteria;
		},
	});
	assert.ok(!turn.includes(PRIVATE_SENTENCE), turn);
	assert.ok(!turn.includes("from-history"), turn);
	// The work is named before anything the review may rest on.
	const record = turn.indexOf("Add the login screen.");
	assert.ok(record !== -1 && record < turn.indexOf('"id": "current"'), turn);
	// The whole standard of the NOT_MET practice comes before the observations and the choice; a MET practice's
	// criteria, and a private practice's, are not read here.
	assert.deepEqual(read, ["describe-what-and-why"]);
	const standard = turn.indexOf(`\`\`\`markdown\n${describeCriteria}\n\`\`\``);
	assert.ok(record < standard && standard < turn.indexOf('"id": "current"'), turn);
	assert.ok(standard < turn.indexOf("Before the review acknowledges a MET observation"), turn);
	// How to write ends the opening turn, after every reference it relies on.
	assert.ok(turn.endsWith(WRITE_CONTRACT), turn);
	assert.match(turn, /"id": "current"/u);
	assert.match(turn, /"name": "Describe what changed and why"/u);
	assert.doesNotMatch(turn, /whyItMatters/u);
	assert.match(turn, /"knownLimitations":/u);
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
		stagedCriteria: () => null,
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
		stagedCriteria: () => null,
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
		errorsOf({
			decisions: [{ observationId: "why", disposition: "ALREADY_SAID", witnessIds: [null] }],
		}).join("\n"),
		/witnessIds must be an array/u,
	);
});

void test("only what was said on this same work, on the work, is supplied as already said", () => {
	const work = "scm.pull_request:https://gitlab.example/group/repo/-/merge_requests/3";
	const history = {
		feedback: [
			{
				channel: "IN_CONTEXT",
				publicEligible: true,
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
				publicEligible: true,
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
			eligibleForAlreadySaid: false,
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
		publicEligible: true,
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
	const derived = priorAdviceWitnesses(said, [
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
	assert.deepEqual(derived.get(`feedback:${historyFeedbackId(1)}`), {
		eligibleForPriorAdvice: true,
		eligibleForAlreadySaid: true,
	});
	assert.deepEqual(derived.get(`feedback:${historyFeedbackId(4)}`), {
		eligibleForPriorAdvice: false,
		eligibleForAlreadySaid: false,
	});
	assert.deepEqual(derived.get("github:review-comment:7"), {
		eligibleForPriorAdvice: true,
		eligibleForAlreadySaid: true,
	});
});

void test("own history omits whole oversized or over-budget entries without hiding later usable advice", () => {
	const artifact = {
		kind: "scm.pull_request",
		url: "https://gitlab.example/group/repo/-/merge_requests/3",
	};
	const entry = (n: number, body: string) => ({
		channel: "IN_CONTEXT",
		publicEligible: true,
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
	const shownWitnesses = priorAdviceWitnesses(view.feedback, []);
	assert.ok(!shownWitnesses.has(`feedback:${historyFeedbackId(1)}`));
	assert.ok(!shownWitnesses.has(`feedback:${historyFeedbackId(3)}`));
	assert.equal(
		shownWitnesses.get(`feedback:${historyFeedbackId(4)}`)?.eligibleForPriorAdvice,
		true,
	);
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
		stagedCriteria: () => null,
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

const eligibleWitnesses = [
	"github:review-comment:7",
	"feedback:00000000-0000-4000-8000-000000000001",
];

const validatorFor = (reviewable: ReadonlyMap<string, ReviewedObservation>, lineNotes = true) =>
	new Ajv({ strict: true, allErrors: true }).compile(
		reviewToolParameters(reviewable, lineNotes, eligibleWitnesses),
	);

void test("the native schema requires complete decisions and each text's fields with only permitted ids", () => {
	const valid = validatorFor(decidedOnly);
	const quiet = {
		decisions: [below("colors"), below("handoff"), below("why"), below("description")],
	};
	assert.ok(valid(quiet), JSON.stringify(valid.errors));
	assert.ok(!valid({}), "a report must deliberately account for every negative");
	assert.ok(
		valid({
			decisions: allRaised,
			summary: { body: "Say why.", basedOn: ["why", "preview", "handoff", "description"] },
			inline: [
				{
					body: "Adapt.",
					basedOn: ["colors"],
					anchor: { observationId: "colors", citationIndex: 1 },
				},
			],
		}),
		JSON.stringify(valid.errors),
	);
	for (const malformed of [
		{ ...quiet, decisions: [{ observationId: "handoff" }] },
		{ ...quiet, decisions: [below("preview"), ...quiet.decisions.slice(1)] },
		{ ...quiet, summary: { body: "Say why." } },
		{ ...quiet, summary: { body: "Say why.", basedOn: [] } },
		{ ...quiet, summary: { body: "Say why.", basedOn: ["why", 42] } },
		{ ...quiet, summary: { body: "Say why.", basedOn: ["elsewhere"] } },
		{ ...quiet, summary: { body: "x".repeat(REVIEW_LIMITS.summaryChars + 1), basedOn: ["why"] } },
		{ ...quiet, inline: [{ body: "Adapt.", basedOn: ["colors"] }] },
		{
			...quiet,
			inline: [{ body: "x", basedOn: ["why"], anchor: { observationId: "why", citationIndex: 0 } }],
		},
		{ ...quiet, inline: Array.from({ length: REVIEW_LIMITS.inlineNotes + 1 }, () => note(0)) },
	]) {
		assert.ok(!valid(malformed), JSON.stringify(malformed));
	}
	const witnessed = {
		...quiet,
		decisions: [
			below("colors"),
			{
				observationId: "handoff",
				disposition: "ALREADY_SAID",
				witnessIds: ["github:review-comment:7"],
			},
			below("why"),
			below("description"),
		],
	};
	assert.ok(valid(witnessed), JSON.stringify(valid.errors));
	assert.ok(
		!valid({
			...witnessed,
			decisions: [
				below("colors"),
				{
					observationId: "handoff",
					disposition: "ALREADY_SAID",
					witnessIds: ["github:issue-comment:9"],
				},
				below("why"),
				below("description"),
			],
		}),
	);
});

void test("with no negatives the required decision list is empty, and work without lines accepts no notes", () => {
	const metOnly = new Map([...observations].filter(([id]) => id === "preview"));
	const valid = validatorFor(metOnly);
	assert.ok(
		valid({ decisions: [], summary: { body: "The preview helps.", basedOn: ["preview"] } }),
	);
	assert.ok(!valid({ decisions: [below("preview")] }));
	assert.ok(
		!valid({
			decisions: [],
			inline: [
				{ body: "x", basedOn: ["preview"], anchor: { observationId: "preview", citationIndex: 0 } },
			],
		}),
	);
	const issue = validatorFor(decidedOnly, false);
	assert.ok(!issue({ decisions: allRaised, inline: [note(0)] }));
	assert.deepEqual(
		reviewToolParameters(decidedOnly, true, []).properties.decisions.items.properties.disposition
			.enum,
		[...DISPOSITIONS],
	);
	const withoutWitness = new Ajv({ strict: true, allErrors: true }).compile(
		reviewToolParameters(decidedOnly, true, []),
	);
	assert.ok(
		!withoutWitness({
			decisions: [
				below("colors"),
				{
					observationId: "handoff",
					disposition: "ALREADY_SAID",
					witnessIds: ["github:review-comment:7"],
				},
				below("why"),
				below("description"),
			],
		}),
	);
	assert.throws(() =>
		reviewToolParameters(new Map([...observations].filter(([id]) => id === "unsure")), true, []),
	);
});

void test("an empty or partial public review leaves negatives undecided, while an empty all-MET review is a valid decision", () => {
	const negative = new Map<string, ReviewedObservation>([
		["one", { practiceSlug: "one", outcome: "NOT_MET", citations: [] }],
		["two", { practiceSlug: "two", outcome: "NOT_MET", citations: [] }],
	]);
	const nothingRead: ReviewContext = { witnesses, standardsInView: new Set() };
	const empty = readReview({}, negative, false, nothingRead);
	assert.ok("errors" in empty);
	assert.match(empty.errors.join("\n"), /one, two has no decision/u);
	const partial = readReview(
		{
			decisions: [raise("one")],
			summary: { body: "The first description needs its purpose.", basedOn: ["one"] },
		},
		negative,
		false,
		nothingRead,
	);
	assert.ok("errors" in partial);
	assert.match(partial.errors.join("\n"), /two has no decision/u);
	// A quiet all-MET review needs no standard: it rests on nothing.
	assert.deepEqual(
		readReview(
			{ decisions: [] },
			new Map([["met", { practiceSlug: "p", outcome: "MET", citations: [] }]]),
			false,
			nothingRead,
		),
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
				inView,
			),
	);
	assert.ok(
		"review" in
			readReview(
				{
					decisions: [raise("summary"), raise("line")],
					summary: { body: "Both concerns.", basedOn: ["summary", "line"] },
				},
				support,
				true,
				inView,
			),
	);
});

// --- The whole review: decisions, witnesses and standards ------------------------------------------

/** Every problem raised; the strength acknowledged only when its practice's standard is in view. */
const wholeReview = (summaryBasedOn: string[]) => ({
	summary: { body: "Say why, and keep the preview.", basedOn: summaryBasedOn },
	inline: [
		{
			body: "Use an adaptive color here.",
			basedOn: ["colors"],
			anchor: { observationId: "colors", citationIndex: 0 },
		},
	],
	decisions: [raise("colors"), raise("why"), below("handoff"), below("description")],
});

void test("malformed lists and legacy selection or withholding authorities are refused whole", () => {
	const spoken = {
		decisions: allRaised,
		summary: { body: "Four concerns.", basedOn: ["colors", "handoff", "why", "description"] },
	};
	assert.ok("review" in readReview(spoken, observations, true, inView));
	assert.ok("review" in readReview({ ...spoken, inline: null }, observations, true, inView));
	for (const container of [{ basedOn: ["colors"], body: "One note." }, "colors", true]) {
		assert.deepEqual(errorsOf({ ...spoken, inline: container }), [
			"inline must be an array of line notes",
		]);
	}
	for (const container of [
		{ observationId: "handoff", disposition: "BELOW_BAR" },
		"BELOW_BAR",
		false,
		null,
	]) {
		assert.match(
			errorsOf({ ...spoken, decisions: container }).join("\n"),
			/decisions is required: an array/u,
		);
	}
	for (const field of ["selected", "selection", "withheld"]) {
		assert.deepEqual(errorsOf({ ...spoken, [field]: [] }), [
			`unknown review field(s): ${field} — a review takes decisions, summary and inline`,
		]);
	}
});

void test("a report decides every negative once, raises only spoken concerns and leaves strengths optional", () => {
	assert.ok("review" in readReview(wholeReview(["why"]), observations, true, inView));
	assert.ok(
		"review" in
			readReview(
				{ decisions: [] },
				new Map([...observations].filter(([id]) => id === "preview")),
				true,
				inView,
			),
	);
	assert.match(
		errorsOf({ ...wholeReview(["why"]), decisions: [raise("colors"), raise("why")] }).join("\n"),
		/handoff, description has no decision/u,
	);
	assert.match(
		errorsOf({
			...wholeReview(["why"]),
			decisions: [...wholeReview(["why"]).decisions, below("description")],
		}).join("\n"),
		/description already has a decision/u,
	);
	assert.match(
		errorsOf({
			...wholeReview(["why"]),
			decisions: [raise("colors"), raise("why"), raise("handoff"), below("description")],
		}).join("\n"),
		/handoff is decided RAISE, and no text speaks about it/u,
	);
	assert.match(
		errorsOf({
			...wholeReview(["why"]),
			decisions: [raise("colors"), raise("why"), below("elsewhere"), below("description")],
		}).join("\n"),
		/elsewhere is not one of the observations/u,
	);
});

void test("a strength is acknowledged only with its practice's whole standard in view when the review was written", () => {
	const notMetOnly: ReviewContext = {
		witnesses,
		standardsInView: new Set(
			[...observations.values()]
				.filter((observation) => observation.outcome === "NOT_MET")
				.map((observation) => observation.practiceSlug),
		),
	};
	// The opening standards of the problems cover the problems; the strength's practice was never shown.
	assert.ok("review" in readReview(wholeReview(["why"]), observations, true, notMetOnly));
	const unread = readReview(wholeReview(["why", "preview"]), observations, true, notMetOnly);
	assert.ok("errors" in unread);
	assert.deepEqual(unread.errors, [
		"summary: it rests on a MET observation of ships-a-preview, whose complete MET reference this review was not written with; read it with read_practice, and send the review in a later turn",
	]);
	// Once that standard is in view, the same whole review is stored.
	const read = readReview(wholeReview(["why", "preview"]), observations, true, {
		witnesses,
		standardsInView: new Set([...notMetOnly.standardsInView, "ships-a-preview"]),
	});
	assert.ok("review" in read);
	assert.deepEqual(read.review.summary?.basedOn, ["why", "preview"]);
});

/** The whole review with one decision about handoff in place of the given one. */
const withholding = (decision: Record<string, unknown>) => ({
	...wholeReview(["why"]),
	decisions: [raise("colors"), raise("why"), below("description"), decision],
});

void test("a withholding that says the advice was given names a statement that may stand as that advice", () => {
	for (const reason of ["ALREADY_SAID", "NO_MATERIAL_CHANGE"]) {
		assert.match(
			errorsOf(withholding({ observationId: "handoff", disposition: reason })).join("\n"),
			new RegExp(`decisions #4: ${reason} names in witnessIds`, "u"),
		);
	}
	assert.match(
		errorsOf(
			withholding({
				observationId: "handoff",
				disposition: "ALREADY_SAID",
				witnessIds: ["github:issue-comment:9"],
			}),
		).join("\n"),
		/github:issue-comment:9, which is shown as context but cannot stand as advice/u,
	);
	assert.match(
		errorsOf(
			withholding({
				observationId: "handoff",
				disposition: "ALREADY_SAID",
				witnessIds: ["github:review-comment:8"],
			}),
		).join("\n"),
		/github:review-comment:8, which is not a statement shown under what was already said/u,
	);
	// A human reviewer's request and Hephaestus's own delivered comment can each stand as the advice. The witness is
	// checked and not stored: the stored decision is its observations and reason.
	for (const witness of [
		"github:review-comment:7",
		"feedback:00000000-0000-4000-8000-000000000001",
	]) {
		const read = readReview(
			withholding({
				observationId: "handoff",
				disposition: "NO_MATERIAL_CHANGE",
				witnessIds: [witness],
			}),
			observations,
			true,
			inView,
		);
		assert.ok("review" in read, JSON.stringify(read));
		assert.deepEqual(read.review.withheld, [
			{ basedOn: ["description"], reason: "BELOW_BAR" },
			{ basedOn: ["handoff"], reason: "NO_MATERIAL_CHANGE" },
		]);
	}
	// Below the bar is a judgement about this reader, not a claim about what was said before.
	assert.ok(
		"review" in
			readReview(
				withholding({ observationId: "handoff", disposition: "BELOW_BAR" }),
				observations,
				true,
				inView,
			),
	);
});

void test("a raised decision names no witness and unknown decision fields are refused", () => {
	assert.match(
		errorsOf({
			...wholeReview(["why"]),
			decisions: [
				raise("colors"),
				{ ...raise("why"), witnessIds: ["github:review-comment:7"] },
				below("handoff"),
				below("description"),
			],
		}).join("\n"),
		/a raised observation names no witness/u,
	);
	assert.match(
		errorsOf({
			...wholeReview(["why"]),
			decisions: [
				raise("colors"),
				{ ...raise("why"), selected: true },
				below("handoff"),
				below("description"),
			],
		}).join("\n"),
		/unknown decision field\(s\): selected/u,
	);
});

void test("a consulted full standard carries only its original permitted MET grounds beside it", () => {
	const original = [
		{
			id: "positive",
			practiceSlug: "preview",
			outcome: "MET",
			publicEligible: true,
			summary: "The example covers an empty state.",
			evidenceRationale: "Source only, not an executed preview.",
			citations: [{ path: "Screen.swift", startLine: 40 }],
		},
		{
			id: "negative",
			practiceSlug: "preview",
			outcome: "NOT_MET",
			publicEligible: true,
			summary: "Different gap",
		},
		{
			id: "private",
			practiceSlug: "preview",
			outcome: "MET",
			publicEligible: false,
			summary: PRIVATE_SENTENCE,
		},
		{
			id: "other",
			practiceSlug: "other",
			outcome: "MET",
			publicEligible: true,
			summary: "Another practice",
		},
	];
	const standard = "### Criteria of `preview`\nFull standard\n";
	const before = JSON.stringify(original);
	const text = consultedStandard(standard, "preview", original);
	assert.ok(text.startsWith(standard));
	const grounded = /```json\n(?<body>[\s\S]*?)\n```/u.exec(text);
	assert.ok(grounded !== null);
	const decoded: unknown = JSON.parse(grounded.groups?.body ?? "");
	assert.deepEqual(decoded, { observations: [original[0]], candidatePriorWitnesses: [] });
	assert.equal(JSON.stringify(original), before);
	assert.ok(!text.includes(PRIVATE_SENTENCE));
});

void test("concern projection preserves new same-practice grounds and wrong old advice without deciding their match", () => {
	// Synthetic counterfactual: the old decoder claim is wrong; the newly changed status check is a distinct gap.
	const artifact = { kind: "scm.pull_request", url: "https://example.test/pull/1" };
	const old = {
		channel: "IN_CONTEXT",
		publicEligible: true,
		artifact,
		id: historyFeedbackId(1),
		deliveredAt: "2026-10-05T09:00:00Z",
		recordedClaimCurrentness: "CURRENT",
		body: "The error object decodes into default repository fields.",
		basedOn: [{ practiceSlug: "handles-errors", outcome: "NOT_MET" }],
	};
	const history = priorPublicFeedback(
		{
			feedback: [
				old,
				{ ...old, id: historyFeedbackId(2), channel: "IN_APP", body: PRIVATE_SENTENCE },
				{
					...old,
					id: historyFeedbackId(3),
					artifact: { ...artifact, url: "https://example.test/pull/2" },
				},
				{ ...old, id: historyFeedbackId(4), withdrawn: true },
				{ ...old, id: historyFeedbackId(5), basedOn: "handles-errors" },
			],
		},
		`${artifact.kind}:${artifact.url}`,
		"2026-10-06T09:00:00Z",
	).feedback;
	const current = [
		{
			id: "new-status",
			practiceSlug: "handles-errors",
			outcome: "NOT_MET",
			publicEligible: true,
			summary: "The added request accepts a non-success response with a valid typed array.",
			evidenceRationale:
				"Conditional typed-array case, not an ordinary error-object decoder claim.",
			citations: [{ path: "Loader.swift", startLine: 51, anchorable: true }],
		},
		{
			id: "caught-decode",
			practiceSlug: "handles-errors",
			outcome: "MET",
			publicEligible: true,
			summary: "Required fields are decoded inside a throwing try/catch.",
			evidenceRationale: "Required id, name and URL have no defaults; errors reach failed.",
			citations: [{ path: "Loader.swift", startLine: 63, anchorable: true }],
		},
	];
	const [negative, positive] = current;
	assert.ok(negative !== undefined && positive !== undefined);
	const practices = [
		{
			slug: "handles-errors",
			name: "Handle errors",
			knownLimitations: ["No execution was captured."],
			whyItMatters: "Survey prose is not needed.",
		},
	];
	const turn = buildReviewTurn({
		sameWork: "Explain failed requests to the user.",
		observations: current,
		alreadySaid: history,
		captured: {
			capturedAt: null,
			recipient: { author: null, authorId: null },
			sources: [],
			statements: [],
		},
		practices,
		undecided: [],
		notReached: [],
		lineNotes: true,
		stagedCriteria: () => "Whole error standard.",
	});
	assert.equal(
		turn.split(old.body).length,
		history.filter((entry) => entry.body === old.body).length + 1,
	);
	assert.ok(turn.indexOf(old.body) < turn.indexOf('"id": "new-status"'));
	assert.ok(!turn.includes(PRIVATE_SENTENCE));
	assert.ok(!turn.includes(positive.evidenceRationale));
	assert.ok(!turn.includes("Survey prose is not needed."));
	const cases = [...turn.matchAll(/```json\n(?<body>[\s\S]*?)\n```/gu)].map((match): unknown =>
		JSON.parse(match.groups?.body ?? "null"),
	);
	const concern = cases.find(
		(entry) =>
			typeof entry === "object" &&
			entry !== null &&
			Reflect.get(entry, "candidatePriorWitnesses") !== undefined,
	);
	assert.ok(typeof concern === "object" && concern !== null);
	assert.deepEqual(Reflect.get(concern, "observations"), [negative]);
	assert.deepEqual(Reflect.get(concern, "candidatePriorWitnesses"), [
		{
			witnessId: `feedback:${historyFeedbackId(1)}`,
			eligibleForAlreadySaid: true,
			eligibleForPriorAdvice: true,
		},
	]);
	const read = consultedStandard(
		"Whole error standard.\n",
		"handles-errors",
		current,
		practices,
		history,
	);
	assert.ok(read.includes(positive.evidenceRationale));
	assert.ok(read.includes("No execution was captured."));
	assert.ok(!read.includes(negative.evidenceRationale));
	// Same-practice history grants a witness, not an automatic suppression of materially new supported advice.
	const currentById = new Map<string, ReviewedObservation>([
		["new-status", { practiceSlug: "handles-errors", outcome: "NOT_MET", citations: [] }],
	]);
	const decision = readReview(
		{
			decisions: [{ observationId: "new-status", disposition: "RAISE" }],
			summary: {
				body: "Check the added response status before accepting a typed array.",
				basedOn: ["new-status"],
			},
		},
		currentById,
		false,
		{ witnesses: priorAdviceWitnesses(history, []), standardsInView: new Set() },
	);
	assert.ok(!("errors" in decision));
});

const ENGAGEMENT_CRITERIA =
	"## The standard\nA review request is answered by a fix, a reasoned decline, or a clarification.\n\n" +
	"## Judge\n- MET: each request has one of those responses.\n- NOT_MET: a request has none.\n\n" +
	"```swift\n// a staged example stays inside its own fence\n```";

void test("the opening carries each NOT_MET practice's whole criteria once, and only a MET practice can be read", () => {
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
		return slug === "engages-with-review" ? ENGAGEMENT_CRITERIA : `Whole criteria of ${slug}.`;
	};

	// Every NOT_MET practice's criterion opens the review, once and whole; a MET practice's stays unread there.
	const opening = notMetReference(reviewable, staged);
	assert.deepEqual(read, ["engages-with-review", "describe-what-and-why"]);
	const criteria = `\`\`\`\`markdown\n${ENGAGEMENT_CRITERIA}\n\`\`\`\``;
	assert.ok(opening.includes(criteria), opening);
	assert.equal(opening.split("### Criteria of `engages-with-review`").length, 2, opening);
	// What the standards are for is said once, before them.
	assert.equal(opening.split(STANDARD_REFERENCE).length, 2, opening);
	assert.ok(opening.indexOf(STANDARD_REFERENCE) < opening.indexOf(criteria), opening);
	assert.ok(opening.includes("Whole criteria of describe-what-and-why."), opening);

	// Only a MET practice can be read; its standard is shown whole and fenced like the opening's.
	assert.deepEqual(readablePractices(reviewable), ["ships-a-preview"]);
	assert.deepEqual(practiceStandard("ships-a-preview", staged), {
		text: "### Criteria of `ships-a-preview`\n```markdown\nWhole criteria of ships-a-preview.\n```\n",
		whole: true,
	});
});

const unreadableCriteria = (): string | null => {
	throw new Error("EACCES");
};

void test("a missing, unreadable, empty or blank criterion is shown as such and never counts as a standard", () => {
	const reviewable = publicObservations([
		{
			id: "held",
			practiceSlug: "describe-what-and-why",
			outcome: "NOT_MET",
			publicEligible: true,
			summary: "The description gives no reason",
			citations: [],
		},
		{
			id: "shared",
			practiceSlug: "describe-what-and-why",
			outcome: "MET",
			publicEligible: true,
			citations: [],
		},
		{
			id: "kept",
			practiceSlug: "private-practice",
			outcome: "MET",
			publicEligible: false,
			citations: [],
		},
	]);
	// A practice with both outcomes is shown in the opening; a practice admission kept private is never readable.
	assert.equal(practiceStandard("describe-what-and-why", () => "Whole.").whole, true);
	assert.deepEqual(readablePractices(reviewable), ["describe-what-and-why"]);
	for (const stagedCriteria of [() => null, () => "", () => " \n\t ", unreadableCriteria]) {
		assert.equal(practiceStandard("describe-what-and-why", stagedCriteria).whole, false);
	}
	// A blank criterion is still shown as staged, never rewritten.
	assert.ok(practiceStandard("describe-what-and-why", () => " \n\t ").text.includes(" \n\t "));

	const missing = notMetReference(reviewable, () => null);
	assert.ok(
		missing.includes("### Criteria of `describe-what-and-why` — not available for this review"),
		missing,
	);
	const empty = notMetReference(reviewable, () => "");
	assert.ok(empty.includes("### Criteria of `describe-what-and-why` — staged empty"), empty);
	const unreadableReference = notMetReference(reviewable, () => {
		throw new Error("EACCES");
	});
	assert.ok(
		unreadableReference.includes("### Criteria of `describe-what-and-why` — could not be read"),
		unreadableReference,
	);
});

void test("public history excludes unqualified legacy and reviewer advice while preserving author advice", () => {
	const artifact = {
		kind: "scm.pull_request",
		url: "https://gitlab.example/group/repo/-/merge_requests/3",
	};
	const row = {
		channel: "IN_CONTEXT",
		artifact,
		body: "Prior advice",
		deliveredAt: "2026-10-05T09:00:00Z",
		recordedClaimCurrentness: "CURRENT",
	};
	const history = {
		feedback: [row, { ...row, publicEligible: false }, { ...row, publicEligible: true }],
	};
	assert.equal(
		priorPublicFeedback(history, `${artifact.kind}:${artifact.url}`, "2026-10-06T09:00:00Z")
			.feedback.length,
		1,
	);
});

void test("a delivery after work capture informs novelty without becoming advice the captured work answered", () => {
	const artifact = {
		kind: "scm.pull_request",
		url: "https://gitlab.example/group/repo/-/merge_requests/3",
	};
	const history = {
		feedback: [
			{
				id: historyFeedbackId(1),
				channel: "IN_CONTEXT",
				publicEligible: true,
				artifact,
				body: "Handle the failure visibly.",
				deliveredAt: "2026-10-07T10:00:00Z",
				recordedClaimCurrentness: "CURRENT",
			},
		],
	};
	const original = JSON.stringify(history);
	const said = priorPublicFeedback(
		history,
		`${artifact.kind}:${artifact.url}`,
		"2026-10-07T09:00:00Z",
		undefined,
		"2026-10-07T11:00:00Z",
	).feedback;
	const late = said.at(0);
	assert.ok(late !== undefined);
	assert.equal(late.eligibleForAlreadySaid, true);
	assert.equal(late.eligibleForPriorAdvice, false);
	const proof = priorAdviceWitnesses(said, []);
	const currentRows = new Map<string, ReviewedObservation>([
		["current", { practiceSlug: "errors", outcome: "NOT_MET", citations: [] }],
	]);
	const selected = (reason: string) =>
		readReview(
			{
				decisions: [
					{
						observationId: "current",
						disposition: reason,
						witnessIds: [`feedback:${historyFeedbackId(1)}`],
					},
				],
			},
			currentRows,
			false,
			{ witnesses: proof, standardsInView: new Set() },
		);
	assert.ok(!("errors" in selected("ALREADY_SAID")));
	assert.ok("errors" in selected("NO_MATERIAL_CHANGE"));
	assert.equal(JSON.stringify(history), original);
	const unknownCapture = priorPublicFeedback(
		history,
		`${artifact.kind}:${artifact.url}`,
		null,
		undefined,
		"2026-10-07T11:00:00Z",
	).feedback;
	const unknownCutoff = unknownCapture.at(0);
	assert.ok(unknownCutoff !== undefined);
	assert.equal(unknownCutoff.eligibleForAlreadySaid, true);
	assert.equal(unknownCutoff.eligibleForPriorAdvice, false);
	const turn = buildReviewTurn({
		sameWork: "Captured work without a known capture time.",
		observations: [],
		undecided: [],
		alreadySaid: unknownCapture,
		ownHistoryReadAt: "2026-10-07T11:00:00Z",
		captured: {
			capturedAt: null,
			recipient: { author: null, authorId: null },
			sources: [],
			statements: [],
		},
		practices: [],
		notReached: [],
		lineNotes: false,
		stagedCriteria: () => null,
	});
	assert.ok(turn.includes('"eligibleForAlreadySaid": true'));
	assert.ok(!turn.includes("was delivered after this work was captured"));
	const unknownDelivery = priorPublicFeedback(
		{ feedback: history.feedback.map((row) => ({ ...row, deliveredAt: null })) },
		`${artifact.kind}:${artifact.url}`,
		"2026-10-07T09:00:00Z",
		undefined,
		"2026-10-07T11:00:00Z",
	).feedback;
	const unknownTime = unknownDelivery.at(0);
	assert.ok(unknownTime !== undefined);
	assert.equal(unknownTime.deliveredAt, null);
	assert.equal(unknownTime.eligibleForAlreadySaid, false);
	assert.equal(unknownTime.eligibleForPriorAdvice, false);
	assert.equal(
		priorPublicFeedback(
			history,
			`${artifact.kind}:${artifact.url}`,
			"2026-10-07T10:30:00Z",
			undefined,
			"2026-10-07T11:00:00Z",
		).feedback[0]?.eligibleForPriorAdvice,
		true,
	);
	assert.equal(
		priorPublicFeedback(
			history,
			`${artifact.kind}:${artifact.url}`,
			"2026-10-07T09:00:00Z",
			undefined,
			"2026-10-07T09:30:00Z",
		).feedback[0]?.eligibleForAlreadySaid,
		false,
	);
	for (const change of [
		{ withdrawn: true },
		{ recordedClaimCurrentness: "UNVERIFIABLE" },
		{ body: undefined },
		{ publicEligible: false },
		{ channel: "IN_CHAT" },
		{ artifact: { ...artifact, url: "https://gitlab.example/group/repo/-/merge_requests/4" } },
	]) {
		const limited = priorPublicFeedback(
			{ feedback: history.feedback.map((row) => ({ ...row, ...change })) },
			`${artifact.kind}:${artifact.url}`,
			"2026-10-07T09:00:00Z",
			undefined,
			"2026-10-07T11:00:00Z",
		);
		assert.ok(limited.feedback.every((row) => !row.eligibleForAlreadySaid));
	}
});

void test("STALE delivered words permit ALREADY_SAID by the read time, not NO_MATERIAL_CHANGE", () => {
	const artifact = {
		kind: "scm.pull_request",
		url: "https://gitlab.example/group/repo/-/merge_requests/3",
	};
	const stale = {
		id: historyFeedbackId(1),
		channel: "IN_CONTEXT",
		publicEligible: true,
		artifact,
		body: "Handle the failure visibly.",
		deliveredAt: "2026-10-07T08:00:00Z",
		recordedClaimCurrentness: "STALE",
	};
	const read = (row: Record<string, unknown>, readAt = "2026-10-07T11:00:00Z") =>
		priorPublicFeedback(
			{ feedback: [row] },
			`${artifact.kind}:${artifact.url}`,
			"2026-10-07T09:00:00Z",
			undefined,
			readAt,
		).feedback;
	const said = read(stale);
	// Delivered before the capture, yet stale: a record of what was said, not a current claim.
	assert.deepEqual(
		said.map((row) => [
			row.recordedClaimCurrentness,
			row.body,
			row.eligibleForAlreadySaid,
			row.eligibleForPriorAdvice,
		]),
		[["STALE", stale.body, true, false]],
	);
	const proof = priorAdviceWitnesses(said, []);
	const currentRows = new Map<string, ReviewedObservation>([
		["current", { practiceSlug: "errors", outcome: "NOT_MET", citations: [] }],
	]);
	const selected = (reason: string) =>
		readReview(
			{
				decisions: [
					{
						observationId: "current",
						disposition: reason,
						witnessIds: [`feedback:${historyFeedbackId(1)}`],
					},
				],
			},
			currentRows,
			false,
			{ witnesses: proof, standardsInView: new Set() },
		);
	assert.ok(!("errors" in selected("ALREADY_SAID")));
	assert.ok("errors" in selected("NO_MATERIAL_CHANGE"));
	assert.equal(read(stale, "2026-10-07T07:30:00Z")[0]?.eligibleForAlreadySaid, false);
	for (const change of [
		{ body: undefined },
		{ body: " " },
		{ withdrawn: true },
		{ recordedClaimCurrentness: undefined },
	]) {
		assert.ok(
			read({ ...stale, ...change }).every(
				(row) => !row.eligibleForAlreadySaid && !row.eligibleForPriorAdvice,
			),
		);
	}
});
