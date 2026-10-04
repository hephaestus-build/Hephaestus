import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import {
	changeHasCitableLines,
	type CitationRepairs,
	citationMatchesArtifact,
	citationsOf,
	describeCitationMismatch,
	isSkipped,
	MAX_BECAUSE_CHARS,
	MAX_SUMMARY_CHARS,
	type NormalizedCitation,
	type NormalizedObservation,
	normalizeCitations,
	normalizeObservation,
	type PracticeQuestion,
	resolveQuote,
	validateEvidenceSources,
	validateSearchScope,
	withoutCoordinates,
} from "../../../main/resources/agent/pi-observation-normalize.ts";

// ── Fixture: one practice of this review, asking three questions ─────────────

const SLUG = "writes-focused-pull-requests";

const QUESTIONS: readonly PracticeQuestion[] = [
	{
		key: "one_concern",
		title: "One concern",
		question: "Does the change address a single concern?",
		yes: "Every changed file serves one goal.",
		no: "The change mixes goals that could land separately.",
	},
	{
		key: "scope_stated",
		title: "Scope stated",
		question: "Does the description state what the change covers?",
		yes: "The description names what the change covers.",
		no: "The description leaves the scope to the diff.",
	},
	{
		key: "tests_follow",
		title: "Tests follow the change",
		question: "Do tests cover the changed behavior?",
		yes: "A test exercises the changed behavior.",
		no: "No test exercises the changed behavior.",
	},
];

const questionsOf = (practiceSlug: string) => (practiceSlug === SLUG ? QUESTIONS : undefined);

const CITATION: Record<string, unknown> = {
	sourceKind: "scm.pull-request.diff",
	artifactPath: "inputs/context/diff.patch",
	path: "src/Auth.java",
	side: "NEW",
	startLine: 10,
	endLine: 10,
	quote: "+ insecure();",
};

/** One answer as the model sends it, citing the observation's first evidence entry: the changed line. */
function sentAnswer(fields: Record<string, unknown> = {}): Record<string, unknown> {
	return {
		cites: [1],
		answer: "NO",
		because: "The diff touches auth and billing in one pull request.",
		...fields,
	};
}

/** A complete set of answers, keyed by question as the tool schema asks. */
function sentAnswers(): Record<string, Record<string, unknown>> {
	return {
		one_concern: sentAnswer(),
		scope_stated: sentAnswer({
			answer: "YES",
			because: "The description lists the auth fix as the change's scope.",
		}),
		tests_follow: sentAnswer({ because: "No test file is part of the change." }),
	};
}

function observation(fields: Record<string, unknown> = {}): Record<string, unknown> {
	return {
		practiceSlug: "writes_focused_pull_requests",
		summary: "PR mixes unrelated changes",
		evidence: [{ ...CITATION }],
		answers: sentAnswers(),
		...fields,
	};
}

/** The observation with the first question's answer sent as given. */
function answering(fields: Record<string, unknown>): Record<string, unknown> {
	return observation({ answers: { ...sentAnswers(), one_concern: sentAnswer(fields) } });
}

/** The observation whose one evidence entry, cited by every answer, is exactly this citation. */
function cited(citation: Record<string, unknown>): Record<string, unknown> {
	return observation({ evidence: [citation] });
}

function without(record: Record<string, unknown>, key: string): Record<string, unknown> {
	const copy = { ...record };
	Reflect.deleteProperty(copy, key);
	return copy;
}

/** Where the run says a cited path was staged, or read from. */
interface Sources {
	sourceOf?: (artifactPath: string) => string | undefined;
	sourceFor?: CitationRepairs["sourceFor"];
}

function normalize(
	raw: unknown,
	notes?: string[],
	{ sourceOf, sourceFor }: Sources = {},
): NormalizedObservation {
	return normalizeObservation(raw, questionsOf, notes, sourceOf, sourceFor);
}

/** The first citation of the first answer: the one `cited` and `answering` set. */
function firstCitation(normalized: NormalizedObservation): NormalizedCitation {
	const citation = normalized.answers[0]?.citations[0];
	if (!citation) {
		throw new Error("expected the first answer to carry a citation");
	}
	return citation;
}

/** The normalized default citation: a NEW-side quote of src/Auth.java [L10]. */
const diffCitation = () => firstCitation(normalize(observation()));

/** The message the model is shown for a refused observation. */
function refusal(raw: unknown, sources: Sources = {}): string {
	try {
		normalize(raw, [], sources);
	} catch (error) {
		return error instanceof Error ? error.message : String(error);
	}
	return assert.fail("expected the observation to be refused");
}

const goodSearch = {
	consulted: ["scm.review-threads"],
	lookedFor: "a review thread raising the migration",
	boundary: "only threads on this pull request; nothing in chat",
};

// ── The observation and its answers ──────────────────────────────────────────

void test("a complete observation records each answer in the practice's question order", () => {
	const sent = observation({
		answers: {
			tests_follow: sentAnswer({ because: "No test file is part of the change." }),
			one_concern: sentAnswer(),
			scope_stated: sentAnswer({ answer: "YES", because: "The description names the scope." }),
		},
	});
	const out = normalize(sent);
	assert.equal(out.practiceSlug, SLUG);
	assert.equal(out.summary, "PR mixes unrelated changes");
	assert.deepEqual(
		out.answers.map((answer) => [answer.question, answer.answer]),
		[
			["one_concern", "NO"],
			["scope_stated", "YES"],
			["tests_follow", "NO"],
		],
	);
	// No outcome, severity or rationale is part of the record: the server derives them from the answers.
	assert.deepEqual(Object.keys(out).toSorted(), ["answers", "practiceSlug", "summary"]);
	assert.deepEqual(out.answers[0], {
		question: "one_concern",
		answer: "NO",
		because: "The diff touches auth and billing in one pull request.",
		citations: [
			{
				sourceKind: "scm.pull-request.diff",
				artifactPath: "inputs/context/diff.patch",
				path: "src/Auth.java",
				side: "NEW",
				startLine: 10,
				endLine: 10,
				quote: "+ insecure();",
			},
		],
	});
	assert.equal(citationsOf(out).length, 3);
});

void test("answers sent as a list naming each question are read as the keyed answers, and said so", () => {
	const keyed = normalize(observation());
	const notes: string[] = [];
	const list = Object.entries(sentAnswers()).map(([question, answer]) => ({
		question,
		...answer,
	}));
	assert.deepEqual(normalize(observation({ answers: list }), notes), keyed);
	assert.deepEqual(notes, ["answers read from a list; nothing to resend"]);
});

void test("a list answering one question twice is refused, naming the question", () => {
	const twice = [
		{ question: "one_concern", ...sentAnswer() },
		{ question: "one_concern", ...sentAnswer({ answer: "YES" }) },
	];
	assert.equal(
		refusal(observation({ answers: twice })),
		"question 'one_concern' is answered twice; send one answer per question",
	);
	assert.equal(
		refusal(observation({ answers: [sentAnswer()] })),
		"each answer in a list names its question in `question`",
	);
	assert.equal(
		refusal(observation({ answers: ["YES"] })),
		"each answer in a list names its question in `question`",
	);
});

void test("answers sent beside summary are read into answers, and said so", () => {
	const { one_concern: oneConcern, ...rest } = sentAnswers();
	const notes: string[] = [];
	const flattened = normalize(
		{
			practiceSlug: "writes_focused_pull_requests",
			summary: "PR mixes unrelated changes",
			evidence: [CITATION],
			...sentAnswers(),
		},
		notes,
	);
	assert.deepEqual(
		flattened.answers.map((answer) => answer.question),
		["one_concern", "scope_stated", "tests_follow"],
	);
	assert.deepEqual(notes, [
		"answers one_concern, scope_stated, tests_follow read from beside summary, into answers; nothing to resend",
	]);
	// Some inside answers and the rest beside it: one set of answers, whichever side each came from.
	assert.equal(
		normalize(observation({ answers: rest, one_concern: oneConcern })).answers.length,
		3,
	);
	// The same question on both sides: which one was meant is not known, so the stray copy is refused.
	assert.equal(
		refusal(observation({ one_concern: oneConcern })),
		"unknown observation field(s): one_concern; an observation has only practiceSlug, scan, evidence, answers and summary",
	);
});

void test("what would settle an open answer is bounded like its reason", () => {
	assert.match(
		refusal(
			answering({
				answer: "UNDETERMINED",
				wouldSettleIt: "x".repeat(601),
			}),
		),
		/^answers\.one_concern\.wouldSettleIt must be at most 600 characters; name the evidence only$/u,
	);
});

void test("an answer field sent beside the questions is pointed back inside an answer", () => {
	assert.match(
		refusal(observation({ answers: { ...sentAnswers(), search: goodSearch } })),
		/^answers has search beside the questions; it belongs inside one answer, e\.g\. answers\.one_concern\.search/u,
	);
});

void test("an answer to a question the practice does not ask is refused with the questions it does ask", () => {
	const answers = sentAnswers();
	assert.equal(
		refusal(observation({ answers: { ...answers, has_tests: sentAnswer() } })),
		`answers has question(s) has_tests that '${SLUG}' does not ask; its questions are one_concern, scope_stated, tests_follow`,
	);
	// A mistyped key is both a foreign answer and a missing one: the refusal names both.
	const { tests_follow: testsFollow, ...rest } = answers;
	assert.equal(
		refusal(observation({ answers: { ...rest, test_follow: testsFollow } })),
		`answers has question(s) test_follow that '${SLUG}' does not ask; its questions are one_concern, scope_stated, tests_follow` +
			`; also: answer every question of '${SLUG}' that its answers do not skip; missing: tests_follow (Tests follow the change)`,
	);
});

void test("every question of the practice must be answered; the refusal names each missing one by title", () => {
	assert.equal(
		refusal(observation({ answers: { scope_stated: sentAnswer() } })),
		`answer every question of '${SLUG}' that its answers do not skip; missing: one_concern (One concern), tests_follow (Tests follow the change)`,
	);
	// No answers at all is every question missing, never an observation with nothing to decide from.
	for (const answers of [{}, []]) {
		assert.match(
			refusal(observation({ answers })),
			new RegExp(
				`^answer every question of '${SLUG}' that its answers do not skip; missing: one_concern \\(One concern\\), scope_stated`,
				"u",
			),
		);
	}
	for (const answers of [undefined, null, "YES", 3]) {
		assert.equal(
			refusal(observation({ answers })),
			"answers is required: an object with one answer per question of the practice",
		);
	}
});

/** A practice whose second question is moot once the first is answered NO. */
const GATED_SLUG = "tests-follow-the-change";
const GATED: readonly PracticeQuestion[] = [
	{
		key: "behavior_changed",
		title: "Behavior changed",
		question: "Does the change alter behavior?",
		yes: "A changed line alters what the code does.",
		no: "Every changed line is formatting, comments or docs.",
	},
	{
		key: "tests_follow",
		title: "Tests follow the change",
		question: "Do tests cover the changed behavior?",
		yes: "A test exercises the changed behavior.",
		no: "No test exercises the changed behavior.",
		skipWhen: [{ question: "behavior_changed", answer: "NO" }],
	},
];

function gated(answers: Record<string, unknown>): NormalizedObservation {
	return normalizeObservation(
		{ practiceSlug: GATED_SLUG, summary: "Docs-only change", evidence: [CITATION], answers },
		(practiceSlug) => (practiceSlug === GATED_SLUG ? GATED : undefined),
	);
}

void test("a question that only grades severity may be left out: it is read as open, with a note", () => {
	const grading = GATED.map((question) =>
		question.key === "tests_follow" ? { ...question, gradesSeverityOnly: true as const } : question,
	);
	const notes: string[] = [];
	const observed = normalizeObservation(
		{
			practiceSlug: GATED_SLUG,
			summary: "Behaviour changes",
			evidence: [CITATION],
			answers: { behavior_changed: sentAnswer({ answer: "YES" }) },
		},
		(practiceSlug) => (practiceSlug === GATED_SLUG ? grading : undefined),
		notes,
	);
	assert.deepEqual(
		observed.answers.map((recorded) => recorded.question),
		["behavior_changed"],
	);
	assert.ok(
		notes.includes(
			"tests_follow left out, so read as open: it only grades severity, and the lower band holds",
		),
		notes.join("\n"),
	);
});

void test("a question its skip condition makes moot may be left unanswered, and only then", () => {
	for (const answer of ["NO", " no "]) {
		const skipped = gated({ behavior_changed: sentAnswer({ answer }) });
		assert.deepEqual(
			skipped.answers.map((recorded) => recorded.question),
			["behavior_changed"],
		);
	}
	for (const answer of ["YES", "UNDETERMINED"]) {
		assert.throws(
			() =>
				gated({
					behavior_changed: sentAnswer({
						answer,
						wouldSettleIt: answer === "UNDETERMINED" ? "the CI log" : undefined,
					}),
				}),
			new RegExp(
				`^Error: answer every question of '${GATED_SLUG}' that its answers do not skip; missing: tests_follow \\(Tests follow the change\\)$`,
				"u",
			),
		);
	}
	// A moot question answered anyway is recorded like any other.
	const answered = gated({
		behavior_changed: sentAnswer(),
		tests_follow: sentAnswer({ because: "No test file is part of the change." }),
	});
	assert.deepEqual(
		answered.answers.map((recorded) => [recorded.question, recorded.answer]),
		[
			["behavior_changed", "NO"],
			["tests_follow", "NO"],
		],
	);
	const [, testsFollow] = GATED;
	assert.ok(testsFollow);
	assert.equal(isSkipped(testsFollow, new Map([["behavior_changed", "NO"]])), true);
	assert.equal(isSkipped(testsFollow, new Map([["behavior_changed", "YES"]])), false);
	assert.equal(isSkipped(testsFollow, new Map()), false);
});

void test("a practice outside this review is refused before its answers are read", () => {
	assert.throws(
		() => normalize(observation({ practiceSlug: "names-things-clearly" })),
		/^Error: practice 'names-things-clearly' is not one of this review's practices$/u,
	);
});

void test("an answer is YES, NO or UNDETERMINED, read in any case; anything else names the three", () => {
	for (const [sent, recorded] of [
		["yes", "YES"],
		[" No ", "NO"],
	] as const) {
		assert.equal(normalize(answering({ answer: sent })).answers[0]?.answer, recorded);
	}
	for (const answer of ["MET", "NOT_MET", "maybe", "", true, 1, null]) {
		assert.equal(
			refusal(answering({ answer })),
			"answers.one_concern.answer must be one of YES, NO, UNDETERMINED",
		);
	}
});

void test("an answer that is not an object is refused with the fields an answer has", () => {
	assert.equal(
		refusal(observation({ answers: { ...sentAnswers(), one_concern: "NO" } })),
		"answers.one_concern must be an object with cites, because and answer",
	);
});

void test("because is required, recorded on one line, and bounded", () => {
	for (const because of [undefined, "", "  \n ", 42]) {
		assert.equal(
			refusal(answering({ because })),
			"answers.one_concern.because is required: one sentence naming the fact in the cited lines that decides it",
		);
	}
	assert.equal(
		normalize(answering({ because: "  The diff\n  touches   auth.  " })).answers[0]?.because,
		"The diff touches auth.",
	);
	const atTheLimit = "x".repeat(MAX_BECAUSE_CHARS);
	assert.equal(normalize(answering({ because: atTheLimit })).answers[0]?.because, atTheLimit);
	// With no sentence boundary within the bound there is nothing to keep, so it is refused.
	assert.equal(
		refusal(answering({ because: `${atTheLimit}x` })),
		`answers.one_concern.because must be at most ${MAX_BECAUSE_CHARS} characters; name the deciding fact only`,
	);
	// Over the bound, it keeps its leading sentences that fit, and says so.
	const notes: string[] = [];
	const first = "The added guard returns early on an empty name.";
	const kept = normalize(
		answering({ because: `${first} ${"It also logs the event and more besides. ".repeat(20)}` }),
		notes,
	).answers[0]?.because;
	assert.ok(kept !== undefined && kept.startsWith(first) && kept.length <= MAX_BECAUSE_CHARS, kept);
	assert.ok(
		notes.some((note) =>
			note.includes(
				`answers.one_concern.because kept to its sentences within ${MAX_BECAUSE_CHARS} characters`,
			),
		),
		notes.join("\n"),
	);
});

void test("an UNDETERMINED answer names what would settle it, and only an UNDETERMINED answer does", () => {
	const open = normalize(
		answering({ answer: "UNDETERMINED", wouldSettleIt: "  the body of issue #7  " }),
	);
	assert.equal(open.answers[0]?.wouldSettleIt, "the body of issue #7");
	for (const wouldSettleIt of [undefined, null, "", "   "]) {
		assert.equal(
			refusal(answering({ answer: "UNDETERMINED", wouldSettleIt })),
			"answers.one_concern is UNDETERMINED and needs wouldSettleIt: the existing evidence that would decide it, e.g. 'the body of issue #7'",
		);
	}
	for (const answer of ["YES", "NO"]) {
		assert.equal(
			refusal(answering({ answer, wouldSettleIt: "the body of issue #7" })),
			"answers.one_concern.wouldSettleIt is only for an UNDETERMINED answer; remove it or answer UNDETERMINED",
		);
	}
	// An empty one says nothing, and a decided answer records none.
	const decided = normalize(answering({ answer: "NO", wouldSettleIt: "" }));
	assert.equal("wouldSettleIt" in (decided.answers[0] ?? {}), false);
});

void test("every answer cites the lines that decide it, whatever it answers", () => {
	const required =
		"answers.one_concern.cites is required: the numbers of the evidence entries that decide it, e.g. [1] for the first";
	for (const answer of ["YES", "NO"]) {
		for (const cites of [undefined, null, []]) {
			assert.equal(refusal(answering({ answer, cites })), required);
		}
	}
	assert.equal(
		refusal(answering({ answer: "UNDETERMINED", wouldSettleIt: "the CI log", cites: [] })),
		required,
	);
});

void test("an answer cites an evidence entry by its number, however the number is written", () => {
	const recorded = diffCitation();
	for (const cites of [1, "1", ["1"], ["[1]"], ["E1"], [" e1 "], [1, "E1", "[1]"]]) {
		const answer = normalize(answering({ cites })).answers[0];
		assert.deepEqual(answer?.citations, [recorded], `cites ${JSON.stringify(cites)}`);
	}
	// Several entries are recorded in the order the answer cites them.
	const second = { ...CITATION, startLine: 12, endLine: 12, quote: "+ audit();" };
	const both = normalize(
		observation({
			evidence: [CITATION, second],
			answers: { ...sentAnswers(), one_concern: sentAnswer({ cites: ["E2", 1] }) },
		}),
	);
	assert.deepEqual(
		both.answers[0]?.citations.map((citation) => citation.startLine),
		[12, 10],
	);
});

void test("a cited number with no evidence entry is refused with the numbers there are", () => {
	for (const cites of [[2], ["E2"], [0], ["first"]]) {
		assert.equal(
			refusal(answering({ cites })),
			`answers.one_concern cites ${JSON.stringify(cites[0])}, but the evidence entries are numbered 1 to 1`,
		);
	}
	// With no evidence listed, no answer has anything to cite, and each is told so.
	assert.equal(
		refusal(observation({ evidence: undefined })),
		["one_concern", "scope_stated", "tests_follow"]
			.map((key) => `answers.${key} cites an evidence entry, but the observation lists no evidence`)
			.join("; also: "),
	);
});

void test("an evidence entry is listed once and every answer citing it records the same lines", () => {
	const observed = normalize(observation());
	const [first, second] = observed.answers.map((answer) => answer.citations[0]);
	assert.deepEqual(first, second);
	// Each answer holds its own copy, so a correction the runner makes to one leaves the others as recorded.
	assert.notEqual(first, second);
});

void test("an evidence entry no answer cites is not recorded, and said so", () => {
	const notes: string[] = [];
	const unused = { ...CITATION, startLine: 12, endLine: 12, quote: "+ audit();" };
	const observed = normalize(observation({ evidence: [CITATION, unused] }), notes);
	assert.deepEqual(
		citationsOf(observed).map((citation) => citation.startLine),
		[10, 10, 10],
	);
	assert.deepEqual(notes, ["evidence 2 cited by no answer, so not recorded"]);
});

void test("citations written inside an answer are read as its own, and the answer is told to list them under evidence", () => {
	const notes: string[] = [];
	const inline = { ...CITATION, startLine: "L12", endLine: 12, quote: "+ audit();" };
	const observed = normalize(answering({ cites: undefined, citations: [inline] }), notes);
	assert.deepEqual(firstCitation(observed), {
		...diffCitation(),
		startLine: 12,
		endLine: 12,
		quote: "+ audit();",
	});
	assert.deepEqual(notes, [
		"answers.one_concern: citations read from inside the answer; list them once under evidence instead",
		'answers.one_concern: startLine "L12" read as 12',
	]);
	// Beside cited entries, they follow them.
	const mixed = normalize(answering({ citations: [inline] }));
	assert.deepEqual(
		mixed.answers[0]?.citations.map((citation) => citation.startLine),
		[10, 12],
	);
	// They are checked like evidence, under the answer that carries them.
	assert.equal(
		refusal(answering({ citations: [{ ...inline, startLine: 0 }] })),
		"answers.one_concern: evidence citation startLine must be a positive integer, received 0; lines are 1-based",
	);
});

void test("an answer's search is recorded with its sources deduplicated and sorted, and only when sent", () => {
	assert.equal("search" in (normalize(observation()).answers[0] ?? {}), false);
	assert.equal("search" in (normalize(answering({ search: null })).answers[0] ?? {}), false);
	const searched = normalize(
		answering({
			search: {
				...goodSearch,
				consulted: [" scm.review-threads ", "scm.pull-request.diff", "scm.review-threads"],
			},
		}),
	);
	// The search is recorded beside the answer's citations, not in place of them.
	assert.equal(firstCitation(searched).path, "src/Auth.java");
	assert.deepEqual(searched.answers[0]?.search, {
		...goodSearch,
		consulted: ["scm.pull-request.diff", "scm.review-threads"],
	});
});

void test("a search names its sources, what it looked for and what it did not cover", () => {
	for (const consulted of [[], [" ", 3], "scm.review-threads"]) {
		assert.equal(
			refusal(answering({ search: { ...goodSearch, consulted } })),
			"answers.one_concern: search.consulted must name at least one source you searched",
		);
	}
	assert.equal(
		refusal(answering({ search: { ...goodSearch, lookedFor: " " } })),
		"answers.one_concern: search.lookedFor is required",
	);
	assert.equal(
		refusal(answering({ search: without(goodSearch, "boundary") })),
		"answers.one_concern: search.boundary is required",
	);
});

void test("an answer carries only its own fields; an outcome or severity on it is refused by name", () => {
	assert.equal(
		refusal(answering({ outcome: "NOT_MET", severity: "MAJOR" })),
		"answers.one_concern has unknown field(s) outcome, severity; an answer has only cites, because, answer, search and wouldSettleIt",
	);
	// The unknown field joins the answer's other problems rather than hiding them.
	assert.match(
		refusal(answering({ confidence: 0.9, because: "" })),
		/^answers\.one_concern has unknown field\(s\) confidence;[^;]*; also: answers\.one_concern\.because is required/u,
	);
});

void test("an outcome, severity or rationale on the observation is refused: the server derives them", () => {
	assert.throws(
		() => normalize(observation({ outcome: "NOT_MET", severity: "MAJOR" })),
		/^Error: outcome, severity is not recorded: answer every question in answers, and Hephaestus derives the outcome and severity from the answers$/u,
	);
	assert.throws(
		() => normalize(observation({ evidenceRationale: "The diff touches auth and billing." })),
		/^Error: evidenceRationale is not recorded: answer every question in answers/u,
	);
	// Evidence is the list of entries the answers cite, never a rationale stated in prose.
	assert.match(
		refusal(observation({ evidence: "The diff touches auth and billing." })),
		/^evidence: citations are required/u,
	);
	// Every stray field is named in one refusal, so one resend fixes them all.
	assert.throws(
		() => normalize(observation({ outcome: "MET", confidence: 0.9 })),
		/^Error: outcome is not recorded: .*; also: unknown observation field\(s\): confidence; an observation has only practiceSlug, scan, evidence, answers and summary$/u,
	);
	// A stray key with no value carries nothing: dropped, and said so.
	for (const outcome of [null, ""]) {
		const notes: string[] = [];
		const out = normalize(observation({ outcome }), notes);
		assert.equal("outcome" in out, false);
		assert.deepEqual(notes, ["empty field(s) outcome dropped"]);
	}
});

void test("any other field on the observation is refused, never interpreted", () => {
	for (const field of [
		"confidence",
		"guidance",
		"suggestedDiffNotes",
		"presence",
		"assessment",
		"citations",
	]) {
		assert.throws(
			() => normalize(observation({ [field]: "legacy" })),
			new RegExp(
				`^Error: unknown observation field\\(s\\): ${field}; an observation has only practiceSlug, scan, evidence, answers and summary$`,
				"u",
			),
		);
	}
	assert.throws(() => normalize("an observation"), /^Error: observation must be an object$/u);
});

void test("an answer nested inside another answer, as an object left open puts it, is read as its own", () => {
	const notes: string[] = [];
	const { scope_stated: scopeStated, ...others } = sentAnswers();
	const observed = normalize(
		observation({
			answers: {
				...others,
				one_concern: { ...sentAnswer({ cites: [1] }), scope_stated: scopeStated },
			},
		}),
		notes,
	);
	assert.deepEqual(
		observed.answers.map((answer) => answer.question),
		["one_concern", "scope_stated", "tests_follow"],
	);
	assert.ok(
		notes.some(
			(note) =>
				note === "answers.one_concern.scope_stated read as answers.scope_stated; nothing to resend",
		),
		notes.join("\n"),
	);
});

void test("the scan is the model's working: accepted before the answers, never recorded", () => {
	const notes: string[] = [];
	const observed = normalize(
		observation({ scan: "Added branches: [L10] calls insecure(); no guard before it." }),
		notes,
	);
	assert.ok(!("scan" in observed), JSON.stringify(observed));
	assert.equal(observed.answers.length, 3);
	assert.ok(!notes.some((note) => note.includes("scan")), notes.join("\n"));
});

void test("a remark beside the fields is left out with a note, on the observation and on an answer", () => {
	const notes: string[] = [];
	const observed = normalize(
		observation({
			evidence_note: "Lines 3-5 are the hunk header.",
			answers: {
				...sentAnswers(),
				one_concern: { ...sentAnswer({ cites: [1] }), skip_note: "none" },
			},
		}),
		notes,
	);
	assert.equal(observed.answers.length, 3);
	assert.ok(
		notes.some((note) => note.startsWith("evidence_note not recorded")),
		notes.join("\n"),
	);
	assert.ok(
		notes.some((note) => note.startsWith("answers.one_concern: skip_note not recorded")),
		notes.join("\n"),
	);
});

void test("an item with no practiceSlug is refused as not an observation, before its answers are read", () => {
	assert.throws(
		() => normalize({ summary: "PR mixes unrelated changes" }),
		/practiceSlug is required: each item of observations is one practice's answers \(received keys: summary\)/u,
	);
	assert.throws(() => normalize({}), /received keys: none/u);
});

void test("practice slugs normalize to one canonical identity", () => {
	const a = normalize(observation({ practiceSlug: "writes_focused_pull_requests" }));
	const b = normalize(observation({ practiceSlug: "WRITES-FOCUSED-PULL-REQUESTS" }));
	assert.equal(a.practiceSlug, SLUG);
	assert.equal(b.practiceSlug, SLUG);
});

void test("a corrected answer or reason is a different observation, not an exact retry", () => {
	const initial = normalize(observation());
	assert.deepEqual(normalize(observation()), initial);
	assert.notDeepEqual(normalize(answering({ answer: "YES" })), initial);
	assert.notDeepEqual(
		normalize(answering({ because: "The diff touches only the auth module." })),
		initial,
	);
});

void test("a one-word summary is refused, because it names nothing on the practice page", () => {
	const oneWord =
		"summary must say what was observed as a short phrase, not one word — e.g. 'Debug print left in the request handler'";
	assert.equal(refusal(observation({ summary: "Test" })), oneWord);
	assert.equal(refusal(observation({ summary: "  Duplication  " })), oneWord);
	assert.equal(normalize(observation({ summary: "No tests" })).summary, "No tests");
	for (const summary of [undefined, "", 1234]) {
		assert.equal(refusal(observation({ summary })), "summary is required");
	}
});

void test("a summary over the bound is refused whole, never recorded as a fragment of itself", () => {
	const quotedTitle =
		"The MR names the issue it implements via 'Closes #1' in the body and '#1' in the title, " +
		"resolved by the platform to issue #1 'Day 1: Make your first merge request'";
	assert.equal(quotedTitle.length, MAX_SUMMARY_CHARS + 3);
	assert.equal(
		refusal(observation({ summary: quotedTitle })),
		`summary must be at most ${MAX_SUMMARY_CHARS} characters; this one is ${quotedTitle.length}. Resend it ` +
			"as a complete phrase that names the behavior; the reasons belong in each answer's because",
	);
	const atTheLimit = `${"x ".repeat(MAX_SUMMARY_CHARS / 2).trim()}x`;
	assert.equal(atTheLimit.length, MAX_SUMMARY_CHARS);
	assert.equal(normalize(observation({ summary: atTheLimit })).summary, atTheLimit);
	// Runs of whitespace are one space: a summary is one line on the page.
	assert.equal(
		normalize(observation({ summary: "PR mixes\n  unrelated   changes" })).summary,
		"PR mixes unrelated changes",
	);
});

void test("every problem of an observation is named in one refusal, each answer by its question", () => {
	assert.equal(
		refusal(
			observation({
				summary: "Test",
				answers: {
					one_concern: sentAnswer({ answer: "MAYBE", because: "" }),
					scope_stated: sentAnswer({ cites: [] }),
					tests_follow: sentAnswer({ answer: "UNDETERMINED" }),
				},
			}),
		),
		[
			"summary must say what was observed as a short phrase, not one word — e.g. 'Debug print left in the request handler'",
			"answers.one_concern.answer must be one of YES, NO, UNDETERMINED",
			"answers.one_concern.because is required: one sentence naming the fact in the cited lines that decides it",
			"answers.scope_stated.cites is required: the numbers of the evidence entries that decide it, e.g. [1] for the first",
			"answers.tests_follow is UNDETERMINED and needs wouldSettleIt: the existing evidence that would decide it, e.g. 'the body of issue #7'",
		].join("; also: "),
	);
});

// ── Citations ────────────────────────────────────────────────────────────────

void test("a line-number refusal names the evidence entry, what was received, and an omitted line as omitted", () => {
	assert.match(
		refusal(cited(without(CITATION, "startLine"))),
		/^evidence: evidence citation startLine is required: the 1-based line of the quoted text in the artifact \(for a quote of the change, the \[L<n>\] coordinate of work\/change\/diff\.patch\)(?:;|$)/u,
	);
	assert.throws(() => normalize(cited({ ...CITATION, startLine: null })), /startLine is required/u);
	assert.throws(
		() => normalize(cited({ ...CITATION, startLine: 0 })),
		/startLine must be a positive integer, received 0; lines are 1-based/u,
	);
	assert.throws(
		() => normalize(cited({ ...CITATION, startLine: "ten" })),
		/received "ten"; lines are 1-based/u,
	);
	assert.throws(
		() => normalize(cited({ ...CITATION, startLine: 10, endLine: 4 })),
		/endLine must be an integer >= startLine, received 4 with startLine 10/u,
	);
	// With several entries, the refusal names the one it is about.
	assert.match(
		refusal(observation({ evidence: [CITATION, { ...CITATION, startLine: 0 }] })),
		/^evidence: entry 2: evidence citation startLine must be a positive integer, received 0; lines are 1-based(?:;|$)/u,
	);
});

void test("citation requires an exact artifact path, and a quote may be left to the coordinates", () => {
	assert.throws(
		() => normalize(cited(without(CITATION, "artifactPath"))),
		/artifactPath is required/u,
	);
	// No quote is a citation by coordinates alone; the runner fills it from the artifact.
	assert.equal(firstCitation(normalize(cited(without(CITATION, "quote")))).quote, "");
});

void test("citation side is present exactly for pull-request diffs", () => {
	// A diff citation may leave the side out; the runner records the side the text is found on.
	assert.equal("side" in firstCitation(normalize(cited(without(CITATION, "side")))), false);
	assert.throws(() => normalize(cited({ ...CITATION, side: "BOTH" })), /side must be OLD or NEW/u);
	// A side on anything but a quote of the change says nothing: surplus, dropped rather than refused.
	const nonDiff = normalize(cited({ ...CITATION, sourceKind: "scm.pull-request.core" }));
	assert.equal("side" in firstCitation(nonDiff), false);
});

void test("change.json pins the range and is not quotable", () => {
	assert.throws(
		() => normalize(cited({ ...CITATION, path: "inputs/context/change.json" })),
		/change\.json pins the reviewed range and is not quotable: quote a changed line from work\/change\/diff\.patch/u,
	);
});

void test("a repository citation names the captured .git/HEAD", () => {
	assert.throws(
		() =>
			normalize(
				cited({ ...CITATION, sourceKind: "scm.repository.tree", artifactPath: "inputs/scm/repo" }),
			),
		/repository citations must use the captured \.git\/HEAD artifact/u,
	);
});

void test("a citation must name a source this run staged, and the artifact that source produced", () => {
	const observed = normalize(observation());
	assert.doesNotThrow(() =>
		validateEvidenceSources(
			observed,
			new Set(["scm.pull-request.diff"]),
			new Map([["inputs/context/diff.patch", "scm.pull-request.diff"]]),
		),
	);
	assert.doesNotThrow(() =>
		validateEvidenceSources(
			observed,
			new Set(["scm.pull-request.diff", "workspace.project-inventory"]),
			new Map([["inputs/context/diff.patch", "scm.pull-request.diff"]]),
		),
	);
	assert.throws(
		() =>
			validateEvidenceSources(
				observed,
				new Set(["scm.pull-request.core", "scm.review-threads"]),
				new Map(),
			),
		/was not available.*scm\.pull-request\.core, scm\.review-threads/u,
	);
	assert.throws(
		() =>
			validateEvidenceSources(
				observed,
				new Set(["scm.pull-request.diff"]),
				new Map([["inputs/context/diff.patch", "scm.pull-request.core"]]),
			),
		/belongs to evidence source 'scm\.pull-request\.core', not 'scm\.pull-request\.diff'/u,
	);
	assert.throws(
		() =>
			validateEvidenceSources(
				observed,
				new Set(["scm.pull-request.diff"]),
				new Map([["inputs/context/change.json", "scm.pull-request.diff"]]),
			),
		/was not staged; the staged artifacts are: inputs\/context\/change\.json\.$/u,
	);
	// The change view is derived in the container; a citation of it is told what the artifact is.
	const derived = normalize(observation());
	firstCitation(derived).artifactPath = "work/change/files.json";
	assert.throws(
		() => validateEvidenceSources(derived, new Set(["scm.pull-request.diff"]), new Map()),
		/work\/ is derived here and is not an artifact: quote a changed line from work\/change\/diff\.patch/u,
	);
});

void test("every answer's citations are checked against the staged sources, not only the first", () => {
	const lastCitesReviews = normalize(
		observation({
			evidence: [
				CITATION,
				{ ...CITATION, sourceKind: "scm.review-threads", artifactPath: "inputs/threads.json" },
			],
			answers: { ...sentAnswers(), tests_follow: sentAnswer({ cites: [2] }) },
		}),
	);
	assert.throws(
		() =>
			validateEvidenceSources(
				lastCitesReviews,
				new Set(["scm.pull-request.diff"]),
				new Map([["inputs/context/diff.patch", "scm.pull-request.diff"]]),
			),
		/evidence source 'scm\.review-threads' was not available/u,
	);
});

void test("diff citations bind the quote to the claimed file and side; a wrong line is corrected when the text is unique", () => {
	const citation = diffCitation();
	const diff =
		"diff --git a/src/Auth.java b/src/Auth.java\n+++ b/src/Auth.java\n@@ -10 +10 @@\n[L10] + insecure();\n";
	assert.equal(citationMatchesArtifact(citation, diff), true);
	assert.equal(citationMatchesArtifact({ ...citation, path: "src/Other.java" }, diff), false);
	assert.equal(citationMatchesArtifact({ ...citation, side: "OLD" }, diff), false);
	assert.deepEqual(resolveQuote({ ...citation, startLine: 11, endLine: 12 }, diff), {
		quote: " insecure();",
		startLine: 10,
		endLine: 10,
	});
});

void test("a quote may drop the diff marker and read the spacing differently; the text must match", () => {
	const citation = diffCitation();
	const diff =
		"diff --git a/src/Auth.java b/src/Auth.java\n+++ b/src/Auth.java\n@@ -10 +10 @@\n[L10] +    insecure();\n";

	// The coordinate pins the line, so spacing cannot confuse two lines; the record is the line's bytes.
	assert.deepEqual(resolveQuote({ ...citation, quote: "insecure();" }, diff), {
		quote: "    insecure();",
	});
	assert.equal(describeCitationMismatch({ ...citation, quote: "    insecure();" }, diff), null);
	assert.equal(describeCitationMismatch({ ...citation, quote: "+    insecure();" }, diff), null);
	// Different text at that coordinate is still refused, trimmed or not.
	assert.match(
		describeCitationMismatch({ ...citation, quote: "secure();" }, diff) ?? "",
		/\[L10\] reads/u,
	);

	// Code that begins with the same character the diff uses as a marker keeps it.
	const flagDiff =
		"diff --git a/run.sh b/run.sh\n+++ b/run.sh\n@@ -10 +10 @@\n[L10] +    -flag --now\n";
	assert.equal(
		describeCitationMismatch({ ...citation, path: "run.sh", quote: "    -flag --now" }, flagDiff),
		null,
	);
	assert.equal(
		describeCitationMismatch({ ...citation, path: "run.sh", quote: "+    -flag --now" }, flagDiff),
		null,
	);
});

/** The mismatch a resolution reports, or nothing when it resolved. */
const mismatch = (result: ReturnType<typeof resolveQuote>) =>
	"mismatch" in result ? result.mismatch : "";

/** A citation of the pull request's title, quoting what the test says it quotes. */
const cite = (quote: string): NormalizedCitation => ({
	sourceKind: "scm.pull-request.core",
	artifactPath: "inputs/context/core.md",
	path: "title",
	startLine: 1,
	endLine: 1,
	quote,
});

void test("a matching diff quote is recorded as the content its lines carry, markers dropped", () => {
	const citation = diffCitation();
	const diff =
		"diff --git a/run.sh b/run.sh\n+++ b/run.sh\n@@ -10,2 +10,2 @@\n[L10] +    -flag --now\n[L11] +  next\n";
	const lines = { ...citation, path: "run.sh", endLine: 11 };
	// Admission reads the blob at the revision, where no marker exists; so the quote must not carry one.
	assert.deepEqual(resolveQuote({ ...lines, quote: "+    -flag --now\n+  next" }, diff), {
		quote: "    -flag --now\n  next",
	});
	assert.deepEqual(resolveQuote({ ...lines, quote: "    -flag --now\n  next" }, diff), {
		quote: "    -flag --now\n  next",
	});
	// Coordinates alone record the lines.
	assert.deepEqual(resolveQuote({ ...lines, quote: "" }, diff), {
		quote: "    -flag --now\n  next",
	});
	// A marker the quote carries that is not the line's own: a blank context line read as an added one.
	const blankContext =
		"diff --git a/a.md b/a.md\n+++ b/a.md\n@@ -9,2 +10,2 @@\n[L10] +text\n[L11]  \n";
	assert.deepEqual(
		resolveQuote(
			{ ...citation, path: "a.md", startLine: 10, endLine: 11, quote: "+text\n+" },
			blankContext,
		),
		{ quote: "text\n" },
	);
	// Blank lines cannot be evidence: admission refuses a blank quote, so the runner does too.
	const blankDiff = "diff --git a/a.md b/a.md\n+++ b/a.md\n@@ -9,0 +10,2 @@\n[L10] +\n[L11] +\n";
	assert.match(
		mismatch(
			resolveQuote(
				{ ...citation, path: "a.md", startLine: 10, endLine: 11, quote: "\n" },
				blankDiff,
			),
		),
		/cited lines are blank/u,
	);
	// A range the change shows only in part records the part it shows; one it shows nothing of is refused.
	assert.deepEqual(resolveQuote({ ...lines, quote: "", endLine: 12 }, diff), {
		quote: "    -flag --now\n  next",
		startLine: 10,
		endLine: 11,
		shortened: true,
	});
	assert.match(
		mismatch(resolveQuote({ ...lines, quote: "", startLine: 20, endLine: 22 }, diff)),
		/no \[L20\]/u,
	);
	// Coordinates alone record the lines up to a bound; past it, the entry is asked to cite fewer.
	const long = `diff --git a/a.md b/a.md\n+++ b/a.md\n@@ -9,0 +10 @@\n[L10] +${"x".repeat(4001)}\n`;
	assert.equal(
		mismatch(
			resolveQuote({ ...citation, path: "a.md", startLine: 10, endLine: 10, quote: "" }, long),
		),
		"[L10]-[L10] is 4001 characters, over the 4000 one entry may record; cite the few lines that show the fact",
	);
	// What does not match is refused with what the line reads.
	assert.match(
		mismatch(resolveQuote({ ...lines, quote: "+    -flag\n+  next" }, diff)),
		/\[L10\] reads/u,
	);
});

void test("a quote is recorded as the artifact spells it: JSON escapes and non-breaking spaces", () => {
	const { side: _side, ...plain } = diffCitation();
	const citation = { ...plain, sourceKind: "scm.linked-work-items", startLine: 2, endLine: 2 };
	const serialized =
		'{\n  "body" : "## Criteria\\n- [x] stored in `diagrams/`\\n- say \\"hi\\""\n}\n';
	// The model quotes the text it read; the record is the escaped form the file holds.
	assert.deepEqual(resolveQuote({ ...citation, quote: '- say "hi"' }, serialized), {
		quote: '- say \\"hi\\"',
	});
	assert.deepEqual(
		resolveQuote({ ...citation, quote: "- [x] stored in `diagrams/`" }, serialized),
		{
			quote: "- [x] stored in `diagrams/`",
		},
	);
	// A non-breaking space read as a space still records the artifact's own byte.
	const nbsp = "* Launch app and open\u00A0`Venues`.\n";
	const first = { ...citation, startLine: 1, endLine: 1 };
	assert.deepEqual(resolveQuote({ ...first, quote: "* Launch app and open `Venues`." }, nbsp), {
		quote: "* Launch app and open\u00A0`Venues`.",
	});
	// A blank line read with a stray space, and non-breaking spaces read as spaces, across lines.
	const steps = "## Testing\n\n1. Launch app and open\u00A0**Coupons**\u00A0tab.\n2. Verify.\n";
	assert.deepEqual(
		resolveQuote(
			{
				...citation,
				startLine: 1,
				endLine: 4,
				quote: "## Testing\n \n1. Launch app and open **Coupons** tab.\n2. Verify.",
			},
			steps,
		),
		{ quote: "## Testing\n\n1. Launch app and open\u00A0**Coupons**\u00A0tab.\n2. Verify." },
	);
	// Coordinates alone record the line, up to a bound.
	assert.deepEqual(resolveQuote({ ...first, quote: "" }, nbsp), {
		quote: "* Launch app and open\u00A0`Venues`.",
	});
	assert.match(
		mismatch(resolveQuote({ ...first, quote: "" }, `${"x".repeat(4001)}\n`)),
		/^\[L1\] is 4001 characters, over the 4000 one entry may record; cite the few lines that show the fact$/u,
	);
	// A quote across the body's line breaks is a reading of the escaped form and resolves to it.
	assert.deepEqual(resolveQuote({ ...citation, quote: "## Criteria\n- [x] stored" }, serialized), {
		quote: "## Criteria\\n- [x] stored",
	});
});

void test("a quote from the other side of the change is refused, however it is written", () => {
	const citation: NormalizedCitation = {
		...diffCitation(),
		side: "NEW",
		startLine: 47,
		endLine: 47,
		quote: '-@RequestMapping({ "api/legacy/" })',
	};
	const diff =
		"--- a/src/Auth.java\n+++ b/src/Auth.java\n@@ -47 +47 @@\n" +
		'[L47] -@RequestMapping({ "api/legacy/" })\n[L47] +@RequestMapping("api/passkeys/")\n';

	assert.match(describeCitationMismatch(citation, diff) ?? "", /\[L47\] reads/u);
});

void test("a quote may carry its exact displayed coordinate", () => {
	const citation = diffCitation();
	const diff =
		"diff --git a/src/Auth.java b/src/Auth.java\n+++ b/src/Auth.java\n@@ -10 +10 @@\n[L10] + insecure();\n";

	assert.equal(describeCitationMismatch({ ...citation, quote: "[L10] + insecure();" }, diff), null);
	// The coordinate still has to be the one being matched, so a quote cannot claim a line it did
	// not read — even when that line's text is in the diff somewhere else.
	assert.match(
		describeCitationMismatch({ ...citation, quote: "[L11] + insecure();" }, diff) ?? "",
		/\[L10\] reads/u,
	);
});

void test("a refused citation says which of the coordinate, the side and the text was wrong", () => {
	const citation = diffCitation();
	const diff =
		"diff --git a/src/Auth.java b/src/Auth.java\n+++ b/src/Auth.java\n@@ -10 +10 @@\n[L10] + insecure();\n";

	assert.equal(describeCitationMismatch(citation, diff), null);
	// The coordinate is not in the diff, but the text is, once: it is recorded where it is.
	assert.deepEqual(resolveQuote({ ...citation, startLine: 11, endLine: 11 }, diff), {
		quote: " insecure();",
		startLine: 10,
		endLine: 10,
	});
	// Neither the coordinate nor the text is in the diff.
	assert.match(
		describeCitationMismatch({ ...citation, startLine: 11, endLine: 11, quote: "gone();" }, diff) ??
			"",
		/no \[L11\] on that side of that path/u,
	);
	// The coordinate is there and says something else, so the refusal shows both.
	assert.match(
		describeCitationMismatch({ ...citation, quote: "+ secure();" }, diff) ?? "",
		/\[L10\] reads "\+ insecure\(\);", not "\+ secure\(\);"/u,
	);
	// The quote and the line span disagree, but the block occurs once: recorded where it is.
	assert.deepEqual(resolveQuote({ ...citation, endLine: 12 }, diff), {
		quote: " insecure();",
		startLine: 10,
		endLine: 10,
	});
	assert.match(
		describeCitationMismatch({ ...citation, endLine: 12, quote: "gone();\nalso();" }, diff) ?? "",
		/quote is 2 line\(s\) and the citation covers 3/u,
	);
});

void test("a citation of the diff view itself is placed at the changed line its quote names, when that is one line", () => {
	const citation = diffCitation();
	const diff =
		"diff --git a/src/Auth.java b/src/Auth.java\n--- a/src/Auth.java\n+++ b/src/Auth.java\n@@ -10 +10,2 @@\n" +
		"[L10] -    secure();\n[L10] +    insecure();\n[L11] +    audit();\n" +
		"diff --git a/src/Log.java b/src/Log.java\n+++ b/src/Log.java\n@@ -3 +3 @@\n[L3] +    audit();\n";
	// Line 92 of the view, not a coordinate of the change: the quote names src/Auth.java [L10] on NEW.
	const ofView = { ...citation, path: "work/change/diff.patch", startLine: 92, endLine: 92 };
	assert.deepEqual(resolveQuote({ ...ofView, quote: "insecure();" }, diff), {
		quote: "    insecure();",
		startLine: 10,
		endLine: 10,
		path: "src/Auth.java",
		side: "NEW",
	});
	assert.deepEqual(resolveQuote({ ...ofView, quote: "secure();" }, diff), {
		quote: "    secure();",
		startLine: 10,
		endLine: 10,
		path: "src/Auth.java",
		side: "OLD",
	});
	// A quote on two changed lines, or on none, is refused with what a citation of the change names.
	assert.match(
		describeCitationMismatch({ ...ofView, quote: "audit();" }, diff) ?? "",
		/work\/change\/diff\.patch is the view of the change, not a file in it: cite the changed file's path[\s\S]*occurs 2 times/u,
	);
	assert.match(
		describeCitationMismatch({ ...ofView, quote: "absent();" }, diff) ?? "",
		/is not a line of the change/u,
	);
});

void test("a range copied from a numbered view of diff.patch is read through both of its ends", () => {
	const citation = diffCitation();
	// View lines 5-9 hold NEW [L10]-[L12] with an OLD line between them; the view's count is not the file's.
	const diff =
		"diff --git a/src/Auth.java b/src/Auth.java\n--- a/src/Auth.java\n+++ b/src/Auth.java\n" +
		"@@ -10,2 +10,3 @@\n[L10]  keep();\n[L11] -old();\n[L11] +insecure();\n[L12] +audit();\n" +
		"@@ -30,0 +31,1 @@\n[L31] +later();\n";
	assert.deepEqual(resolveQuote({ ...citation, startLine: 5, endLine: 8, quote: "" }, diff), {
		quote: "keep();\ninsecure();\naudit();",
		startLine: 10,
		endLine: 12,
	});
	// A range that starts on the file's own header, as a read of the view by file shows it, cites its lines.
	assert.deepEqual(resolveQuote({ ...citation, startLine: 1, endLine: 8, quote: "" }, diff), {
		quote: "keep();\ninsecure();\naudit();",
		startLine: 10,
		endLine: 12,
	});
	// A range across a hunk boundary records the file's leading run of lines, and says it shortened it.
	assert.deepEqual(resolveQuote({ ...citation, startLine: 5, endLine: 10, quote: "" }, diff), {
		quote: "keep();\ninsecure();\naudit();",
		startLine: 10,
		endLine: 12,
		shortened: true,
	});
});

void test("a range of diff.patch itself, by coordinates alone, is read as the one file and side it shows", () => {
	const citation = diffCitation();
	const diff =
		"diff --git a/src/Auth.java b/src/Auth.java\n--- a/src/Auth.java\n+++ b/src/Auth.java\n" +
		"@@ -10,0 +10,2 @@\n[L10] + insecure();\n[L11] + audit();\n" +
		"diff --git a/src/Log.java b/src/Log.java\n--- a/src/Log.java\n+++ b/src/Log.java\n@@ -3,0 +3 @@\n[L3] + log();\n";
	const ofView = { ...citation, path: "work/change/diff.patch", quote: "" };
	// View lines 1-6 are src/Auth.java's header and its two added lines.
	assert.deepEqual(resolveQuote({ ...ofView, startLine: 1, endLine: 6 }, diff), {
		quote: " insecure();\n audit();",
		startLine: 10,
		endLine: 11,
		path: "src/Auth.java",
		side: "NEW",
	});
	// A range across two files names no one file: the session is told what a citation of the change names.
	assert.match(
		describeCitationMismatch({ ...ofView, startLine: 1, endLine: 11 }, diff) ?? "",
		/work\/change\/diff\.patch is the view of the change, not a file in it/u,
	);
});

void test("a coordinate copied from a numbered view of diff.patch is read as the line it names", () => {
	const citation = diffCitation();
	// The text occurs twice in the file; the session cites line 7 of diff.patch, as sed -n prints it.
	const diff =
		"diff --git a/src/Auth.java b/src/Auth.java\n--- a/src/Auth.java\n+++ b/src/Auth.java\n" +
		"@@ -10,0 +10,1 @@\n[L10] + insecure();\n@@ -20,0 +21,1 @@\n[L21] + insecure();\n";
	assert.deepEqual(
		resolveQuote({ ...citation, startLine: 7, endLine: 7, quote: "insecure();" }, diff),
		{ quote: " insecure();", startLine: 21, endLine: 21 },
	);
	// A view line that is not a line of the cited file still names nothing: the choice is the session's.
	assert.match(
		describeCitationMismatch(
			{ ...citation, startLine: 4, endLine: 4, quote: "insecure();" },
			diff,
		) ?? "",
		/occurs at \[L10\], \[L21\] — cite the one you mean/u,
	);
});

void test("a file the diff shows without numbered lines is refused with what to cite instead", () => {
	const citation = diffCitation();
	const diff =
		"diff --git a/src/Auth.java b/src/Auth.java\n--- a/src/Auth.java\n+++ b/src/Auth.java\n" +
		"@@ -10 +10 @@\n[L10] + insecure();\n" +
		"diff --git a/logo.png b/logo.png\nnew file mode 100644\nBinary files /dev/null and b/logo.png differ\n" +
		"diff --git a/old.txt b/new.txt\nsimilarity index 100%\nrename from old.txt\nrename to new.txt\n" +
		"diff --git a/gone.txt b/gone.txt\ndeleted file mode 100644\n--- a/gone.txt\n+++ /dev/null\n" +
		"@@ -1 +0,0 @@\n[L1] -bye\n";
	const at = (path: string, side: "OLD" | "NEW" = "NEW") =>
		describeCitationMismatch({ ...citation, path, side, startLine: 361, endLine: 361 }, diff) ?? "";

	// A binary file and a rename without edits have a header only: the commit that touches them is citable.
	assert.match(at("logo.png"), /no numbered lines in the diff[\s\S]*commits\.json/u);
	assert.match(at("new.txt"), /no numbered lines in the diff/u);
	assert.match(at("old.txt", "OLD"), /no numbered lines in the diff/u);
	// A deleted file's lines are on the old side.
	assert.match(at("gone.txt"), /no lines on the NEW side[\s\S]*on the OLD side/u);
	// A path the change never names is not a file of the change at all.
	assert.match(at("elsewhere.txt"), /the change does not touch elsewhere\.txt/u);
});

void test("removed-line citations use old-side coordinates", () => {
	const citation: NormalizedCitation = {
		...diffCitation(),
		side: "OLD",
		startLine: 8,
		endLine: 8,
		quote: "- requireAdmin();",
	};
	const diff =
		"--- a/src/Auth.java\n+++ b/src/Auth.java\n@@ -8 +8 @@\n[L8] - requireAdmin();\n[L8] + allowAll();\n";
	assert.equal(citationMatchesArtifact(citation, diff), true);
	assert.equal(citationMatchesArtifact({ ...citation, side: "NEW" }, diff), false);
});

void test("a claim about an earlier review is bound to the staged history like any other citation", () => {
	const observed = normalize(
		cited({
			sourceKind: "hephaestus.observation-history",
			artifactPath: "inputs/history/observations.json",
			path: "inputs/history/observations.json",
			startLine: 1,
			endLine: 1,
			quote: '"recurrenceKey": "rec-1"',
		}),
	);
	const artifacts = new Map([
		["inputs/history/observations.json", "hephaestus.observation-history"],
		["inputs/context/diff.patch", "scm.pull-request.diff"],
	]);
	const staged = new Set(["scm.pull-request.diff", "hephaestus.observation-history"]);

	assert.doesNotThrow(() => validateEvidenceSources(observed, staged, artifacts));
	const bytes = '{"observations":[{"recurrenceKey": "rec-1","title":"Caught and ignored"}]}';
	const history = firstCitation(observed);
	assert.equal(citationMatchesArtifact(history, bytes), true);
	const invented = { ...history, quote: '"recurrenceKey": "invented"' };
	assert.equal(citationMatchesArtifact(invented, bytes), false);
	// The refusal shows what the cited lines hold: a body serialized into one JSON line is one line.
	assert.equal(
		describeCitationMismatch(invented, bytes),
		`[L1] reads ${JSON.stringify(bytes)}, not ${JSON.stringify(invented.quote)}`,
	);
	assert.equal(
		describeCitationMismatch({ ...invented, startLine: 3, endLine: 3 }, `${bytes}\n`),
		"the artifact has 1 line(s), so there is no [L3]",
	);
	// A quote across a serialized body's line breaks is a reading of the escaped form, and resolves to it;
	// text that is not there at all is refused with how to quote such a line.
	const serialized = '{"body": "## Criteria\\n- [x] stored in `diagrams/`\\n- [x] embedded"}\n';
	assert.deepEqual(
		resolveQuote({ ...invented, quote: "## Criteria\n- [x] stored in `diagrams/`" }, serialized),
		{ quote: "## Criteria\\n- [x] stored in `diagrams/`" },
	);
	assert.match(
		describeCitationMismatch({ ...invented, quote: "## Criteria\n- [x] missing" }, serialized) ??
			"",
		/^\[L1\] reads .*; this is a JSON string whose line breaks are the two characters \\n, so quote a fragment from between two of them, or spell them as the line does$/u,
	);
	assert.equal(
		describeCitationMismatch({ ...invented, quote: "- [x] stored in `diagrams/`" }, serialized),
		null,
	);
});

void test("a citation rejects typographic substitutions not present in the artifact", () => {
	const content = 'Resolve "Connect data between screens" — see the plan';
	assert.equal(
		citationMatchesArtifact(cite('Resolve "Connect data between screens"'), content),
		true,
	);
	assert.equal(
		citationMatchesArtifact(cite("Resolve “Connect data between screens”"), content),
		false,
	);
	assert.equal(citationMatchesArtifact(cite("see the plan"), content), true);
});

void test("a citation rejects invented artifact text", () => {
	const content = 'Resolve "Connect data between screens"';
	assert.equal(
		citationMatchesArtifact(cite("Resolve “Disconnect data between screens”"), content),
		false,
	);
	assert.equal(citationMatchesArtifact(cite("a rationale the author never wrote"), content), false);
});

void test("historical citations preserve a full revision for trusted admission", () => {
	const citation = {
		sourceKind: "scm.repository.tree",
		artifactPath: "inputs/scm/repo/.git/HEAD",
		path: "deleted.ts",
		revision: "a".repeat(40),
		startLine: 3,
		quote: "historical text",
	};
	assert.equal(normalizeCitations([citation])[0]?.revision, citation.revision);
	assert.throws(
		() => normalizeCitations([{ ...citation, revision: "HEAD~1" }]),
		/revision must be a full commit SHA, received "HEAD~1"/u,
	);
	// Whether a revision applies is decided once the manifest has settled the source, so it is kept
	// here; the runner drops it from any citation that is not of the repository, with a note.
	assert.equal(
		normalizeCitations([{ ...citation, sourceKind: "scm.issue.core" }])[0]?.revision,
		citation.revision,
	);
});

void test("a citation is completed from the manifest and its line numbers read as written", () => {
	const notes: string[] = [];
	const staged = new Map([
		["inputs/context/metadata.json", "scm.pull-request.core"],
		["inputs/context/change.json", "scm.pull-request.diff"],
	]);
	const sourceOf = (artifact: string) => staged.get(artifact);
	const [record, change] = normalizeCitations(
		[
			{ path: "inputs/context/metadata.json", startLine: "[L3]", quote: "x" },
			{
				artifactPath: "inputs/context/change.json",
				path: "src/Auth.java",
				side: "NEW",
				startLine: "L10",
				endLine: "12",
				quote: "y",
			},
		],
		{ sourceOf, notes },
	);
	assert.deepEqual(
		[record?.artifactPath, record?.sourceKind, record?.startLine],
		["inputs/context/metadata.json", "scm.pull-request.core", 3],
	);
	assert.deepEqual(
		[change?.sourceKind, change?.startLine, change?.endLine],
		["scm.pull-request.diff", 10, 12],
	);
	// Naming a staged record by its path is how a citation names its source, so filling it in is no repair
	// and is not echoed; a line number read from how it was written is.
	assert.deepEqual(notes, [
		'citation 1: startLine "[L3]" read as 3',
		'citation 2: startLine "L10" read as 10',
		'citation 2: endLine "12" read as 12',
	]);
	// In an observation, the notes name the evidence entry, and a lone entry needs no number.
	const single: string[] = [];
	const observed = normalize(
		cited({ path: "inputs/context/metadata.json", startLine: "[L3]", quote: "x" }),
		single,
		{ sourceOf },
	);
	assert.equal(firstCitation(observed).sourceKind, "scm.pull-request.core");
	assert.deepEqual(single, ['evidence: startLine "[L3]" read as 3']);
	const several: string[] = [];
	normalize(
		observation({
			evidence: [CITATION, { ...CITATION, startLine: "L10" }],
			answers: { ...sentAnswers(), tests_follow: sentAnswer({ cites: [2] }) },
		}),
		several,
	);
	assert.deepEqual(several, ['evidence: entry 2: startLine "L10" read as 10']);
	// What the manifest does not know is still asked for, naming the citation it is missing from.
	assert.throws(
		() =>
			normalizeCitations(
				[
					{ path: "a.ts", startLine: 1 },
					{ path: "b.ts", startLine: 1 },
				],
				{ sourceOf: () => undefined },
			),
		/^Error: citation 1: a\.ts is neither a staged record nor a file the change or the checkout holds: name the record \(context\/…\) or the changed file as the brief shows it$/u,
	);
});

void test("an evidence entry's anchor is kept trimmed for the runner to find the line by, and dropped when blank", () => {
	const [anchored, blank, absent] = normalizeCitations([
		{ ...CITATION, anchor: "  insecure();\t" },
		{ ...CITATION, anchor: " \n" },
		CITATION,
	]);
	assert.equal(anchored?.anchor, "insecure();");
	assert.ok(blank !== undefined && !("anchor" in blank));
	assert.ok(absent !== undefined && !("anchor" in absent));
	assert.equal(firstCitation(normalize(cited({ ...CITATION, anchor: " auth " }))).anchor, "auth");
});

/** Where the runner reads a path that is no staged record: a line of the change, or a file of the checkout. */
const DIFF_SOURCE = {
	artifactPath: "inputs/context/change.json",
	sourceKind: "scm.pull-request.diff",
};
const TREE_SOURCE = {
	artifactPath: "inputs/scm/repo/.git/HEAD",
	sourceKind: "scm.repository.tree",
};

function readFrom(asked: { path: string; side: string | null }[] = []): Sources {
	return {
		sourceOf: (artifact) =>
			artifact === "inputs/context/metadata.json" ? "scm.pull-request.core" : undefined,
		sourceFor: (citation) => {
			asked.push(citation);
			if (citation.side !== null || citation.path === "work/change/diff.patch") {
				return DIFF_SOURCE;
			}
			return citation.path.startsWith("inputs/") || citation.path.startsWith("work/")
				? undefined
				: TREE_SOURCE;
		},
	};
}

void test("a derived list of the change's files is left out of the evidence; an answer citing only it is refused", () => {
	const notes: string[] = [];
	const files = { path: "work/change/files.json", startLine: 2 };
	const observed = normalize(
		observation({
			evidence: [{ path: "src/Auth.java", side: "NEW", startLine: 10 }, files],
			answers: {
				one_concern: sentAnswer({ cites: [1, 2] }),
				scope_stated: sentAnswer({ cites: [1] }),
				tests_follow: sentAnswer({ cites: [1] }),
			},
		}),
		notes,
		readFrom(),
	);
	assert.deepEqual(
		observed.answers.map((answer) => answer.citations.map((citation) => citation.path)),
		[["src/Auth.java"], ["src/Auth.java"], ["src/Auth.java"]],
	);
	assert.ok(
		notes.some((note) =>
			/entries 2 name a list of the change's files derived here, so they are not recorded/u.test(
				note,
			),
		),
		notes.join("\n"),
	);
	assert.throws(
		() =>
			normalize(
				observation({
					evidence: [{ path: "src/Auth.java", side: "NEW", startLine: 10 }, files],
					answers: {
						one_concern: sentAnswer({ cites: [2] }),
						scope_stated: sentAnswer({ cites: [1] }),
						tests_follow: sentAnswer({ cites: [1] }),
					},
				}),
				[],
				readFrom(),
			),
		/answers\.one_concern cites only a list of the change's files derived here, which is not evidence: cite a changed line/u,
	);
});

void test("a checkout file named by its workspace path is read at its path inside the checkout, with a note", () => {
	const notes: string[] = [];
	const checkout = { artifactPath: "repos/reviewed/.git/HEAD", sourceKind: "scm.repository.tree" };
	const observed = normalize(
		observation({
			evidence: [{ path: "repos/reviewed/README.md", startLine: 3 }],
			answers: {
				one_concern: sentAnswer({ cites: [1] }),
				scope_stated: sentAnswer({ cites: [1] }),
				tests_follow: sentAnswer({ cites: [1] }),
			},
		}),
		notes,
		{ sourceFor: () => checkout },
	);
	assert.deepEqual(
		citationsOf(observed).map(({ artifactPath, path }) => ({ artifactPath, path }))[0],
		{ artifactPath: "repos/reviewed/.git/HEAD", path: "README.md" },
	);
	assert.ok(
		notes.some((note) =>
			note.includes(
				"path repos/reviewed/README.md read as README.md, its path inside the checkout",
			),
		),
		notes.join("\n"),
	);
});

void test("an entry naming no staged record is read from the change when it has a side, else from the checkout", () => {
	const asked: { path: string; side: string | null }[] = [];
	const notes: string[] = [];
	const observed = normalize(
		observation({
			evidence: [
				{ path: "src/Auth.java", side: "new", startLine: 10, quote: "+ insecure();" },
				{ path: "README.md", startLine: 3, quote: "Run make." },
				{ path: "inputs/context/metadata.json", startLine: 1, quote: "{" },
			],
			answers: {
				one_concern: sentAnswer({ cites: [1] }),
				scope_stated: sentAnswer({ cites: [2] }),
				tests_follow: sentAnswer({ cites: [3] }),
			},
		}),
		notes,
		readFrom(asked),
	);
	assert.deepEqual(
		citationsOf(observed).map(({ sourceKind, artifactPath, path, side }) => ({
			sourceKind,
			artifactPath,
			path,
			side,
		})),
		[
			{ ...DIFF_SOURCE, path: "src/Auth.java", side: "NEW" },
			{ ...TREE_SOURCE, path: "README.md", side: undefined },
			{
				sourceKind: "scm.pull-request.core",
				artifactPath: "inputs/context/metadata.json",
				path: "inputs/context/metadata.json",
				side: undefined,
			},
		],
	);
	// The side is passed as the run reads it; a staged record is its own artifact and is never asked about.
	assert.deepEqual(asked, [
		{ path: "src/Auth.java", side: "NEW" },
		{ path: "README.md", side: null },
	]);
	// Naming the source is not a repair: nothing is echoed.
	assert.deepEqual(notes, []);
	// The change view named as the path is a line of the change.
	assert.equal(
		firstCitation(
			normalize(
				cited({ path: "work/change/diff.patch", startLine: 92, quote: "insecure();" }),
				[],
				readFrom(),
			),
		).sourceKind,
		DIFF_SOURCE.sourceKind,
	);
	// An entry that states its source but names no staged artifact is read from its path like any other: the
	// artifact is not a field the model is asked for, so it is never refused for leaving it out.
	const statedAsked: { path: string; side: string | null }[] = [];
	assert.deepEqual(
		firstCitation(normalize(cited(without(CITATION, "artifactPath")), [], readFrom(statedAsked))),
		firstCitation(normalize(cited(CITATION), [], readFrom())),
	);
	assert.deepEqual(statedAsked, [{ path: "src/Auth.java", side: "NEW" }]);
});

void test("an entry naming a derived view or a path nothing holds is refused with what to cite instead", () => {
	assert.match(
		refusal(cited({ path: "work/change/files.json", startLine: 1, quote: "x" }), readFrom()),
		/^evidence: work\/change\/files\.json is a view derived here, not evidence: cite a changed line of the file itself \(path and side, lines from diff\.patch\), or, for which files a commit changed, its "path" lines in commits\.json(?:;|$)/u,
	);
	assert.match(
		refusal(cited({ path: "inputs/context/other.json", startLine: 1, quote: "x" }), readFrom()),
		/^evidence: inputs\/context\/other\.json is neither a staged record nor a file the change or the checkout holds: name the record \(context\/…\) or the changed file as the brief shows it(?:;|$)/u,
	);
	assert.match(
		refusal(cited({ startLine: 1, quote: "x" }), readFrom()),
		/^evidence: an entry without a path is neither a staged record/u,
	);
});

void test("live practice fixture permits a citation of its planted credential", () => {
	const diff = readFileSync(new URL("live-practice/diff.patch", import.meta.url), "utf8");
	assert.equal(
		describeCitationMismatch(
			{
				sourceKind: "scm.pull-request.diff",
				artifactPath: "inputs/context/diff.patch",
				path: "LoginService.swift",
				side: "NEW",
				startLine: 4,
				endLine: 4,
				quote: '    private let apiKey = "sk-live-AKIAIOSFODNN7EXAMPLE-prod-2026"',
			},
			diff,
		),
		null,
	);
});

void test("normalization preserves the quoted source indentation and trailing spaces", () => {
	const quote = "    insecure();  ";
	assert.equal(firstCitation(normalize(cited({ ...CITATION, quote }))).quote, quote);
});

void test("a serialized-source quote at the wrong lines is recorded where it occurs, when that is one place", () => {
	const citation: NormalizedCitation = {
		sourceKind: "scm.pull-request.core",
		artifactPath: "inputs/context/metadata.json",
		path: "inputs/context/metadata.json",
		startLine: 1,
		endLine: 1,
		quote: '"changed_files" : 1',
	};
	const content = '{\n  "changed_files" : 1\n}\n';
	assert.deepEqual(resolveQuote(citation, content), {
		quote: '"changed_files" : 1',
		startLine: 2,
		endLine: 2,
	});
	// The same text in two places is not relocated: the refusal names both.
	assert.match(
		describeCitationMismatch(citation, `${content}  "changed_files" : 1\n`) ?? "",
		/it occurs at \[L2\], \[L4\] — cite the one you mean/u,
	);
	assert.equal(citationMatchesArtifact({ ...citation, startLine: 2, endLine: 2 }, content), true);
	assert.equal(
		citationMatchesArtifact(
			{ ...citation, startLine: 2, endLine: 2, quote: '  "changed_files" : 1\n' },
			content,
		),
		true,
	);
});

void test("normalization preserves raw quote bytes and local preflight matches server line semantics", () => {
	const diff =
		"--- a/src/Auth.java\n+++ b/src/Auth.java\n@@ -10,2 +10,2 @@\n[L10] +  Trivio:  # TODO: Adjust Name\n[L11] +\n";
	for (const quote of [
		"  Trivio:  # TODO: Adjust Name",
		"  Trivio:  # TODO: Adjust Name\n",
		"+  Trivio:  # TODO: Adjust Name\r\n",
	]) {
		const citation = firstCitation(normalize(cited({ ...CITATION, quote })));
		assert.equal(citation.quote, quote);
		assert.equal(describeCitationMismatch(citation, diff), null);
	}
	const quote = "  Trivio:  # TODO: Adjust Name\r\n\r\n";
	const citation = firstCitation(normalize(cited({ ...CITATION, quote, endLine: 11 })));
	assert.equal(citation.quote, quote);
	assert.equal(describeCitationMismatch(citation, diff), null);
});

void test("citation coordinates are bounded", () => {
	for (const line of [2_147_483_648, 4_294_967_306, Number.MAX_SAFE_INTEGER + 1]) {
		assert.throws(() => normalize(cited({ ...CITATION, startLine: line })), /startLine/u);
		assert.throws(
			() => normalize(cited({ ...CITATION, startLine: 10, endLine: line })),
			/endLine/u,
		);
	}
});

void test("annotated source text is not parsed as a header and Unicode separators remain source text", () => {
	const citation = diffCitation();
	const diff =
		"--- a/src/Auth.java\n+++ b/src/Auth.java\n[L10] --- SQL comment\n[L10] +++ value\u2028tail\u2029end\n";
	assert.equal(
		describeCitationMismatch({ ...citation, side: "OLD", quote: "-- SQL comment" }, diff),
		null,
	);
	assert.equal(
		describeCitationMismatch({ ...citation, quote: "++ value\u2028tail\u2029end" }, diff),
		null,
	);
	assert.notEqual(
		describeCitationMismatch(
			{ ...citation, endLine: 11, quote: "x\n\n" },
			"--- a/src/Auth.java\n+++ b/src/Auth.java\n[L10] +x\n[L11] ",
		),
		null,
	);
	assert.equal(
		describeCitationMismatch({ ...citation, path: '"', quote: "x" }, '--- "\n+++ "\n[L10] +x\n'),
		null,
	);
});

void test("normalization preserves nonblank citation path identifiers", () => {
	const supplied = {
		...CITATION,
		path: " source file ",
		artifactPath: "inputs/context/ captured file ",
	};
	const citation = firstCitation(normalize(cited(supplied)));
	assert.equal(citation.path, supplied.path);
	assert.equal(citation.artifactPath, supplied.artifactPath);
	for (const field of ["path", "artifactPath"]) {
		assert.throws(
			() => normalize(cited({ ...CITATION, [field]: " \t\n" })),
			new RegExp(`${field} is required`, "u"),
		);
	}
});

void test("a quote copied with the brief's line coordinates is stored without them", () => {
	assert.equal(withoutCoordinates("[L15] to test the winner", 15), "to test the winner");
	assert.equal(withoutCoordinates("[L15] first\n[L16] second\n", 15), "first\nsecond\n");
	// A coordinate that names another line is text, and stays.
	assert.equal(withoutCoordinates("[L9] elsewhere", 15), "[L9] elsewhere");
	assert.equal(withoutCoordinates("plain", 3), "plain");
});

// ── Search scope ─────────────────────────────────────────────────────────────

void test("an answer from direct evidence owes no search, whatever the practice reads exhaustively", () => {
	assert.doesNotThrow(() =>
		validateSearchScope(
			normalize(observation()),
			new Set(["scm.review-threads"]),
			new Set(["scm.review-threads"]),
		),
	);
});

void test("an answer resting on absence searched every source the practice reads exhaustively", () => {
	const searched = normalize(answering({ search: goodSearch }));
	const available = new Set(["scm.review-threads", "scm.linked-work-items", "scm.issue.core"]);
	// A practice that reads nothing exhaustively has no boundary for any absence to rest on.
	assert.throws(
		() => validateSearchScope(searched, new Set(), available),
		/^Error: answers\.one_concern has a search, and this practice reads no source exhaustively, so no search can bound an absence: drop search and cite the lines that show the answer, or answer UNDETERMINED with wouldSettleIt$/u,
	);
	assert.doesNotThrow(() =>
		validateSearchScope(searched, new Set(goodSearch.consulted), available),
	);
	assert.throws(
		() =>
			validateSearchScope(searched, new Set(["scm.issue.core", "scm.review-threads"]), available),
		/^Error: answers\.one_concern rests on an absence in scm\.issue\.core as well: add it to its search\.consulted once you have searched it$/u,
	);
	assert.throws(
		() => validateSearchScope(searched, available, available),
		/^Error: answers\.one_concern rests on an absence in scm\.issue\.core, scm\.linked-work-items as well: add them to its search\.consulted once you have searched them$/u,
	);
});

void test("an absence over a source the practice must search and the run never staged cannot be shown", () => {
	const searched = normalize(answering({ search: goodSearch }));
	// Never "add it to search.consulted": that source would then be refused as not staged, and the two
	// refusals would send the session round in a loop.
	assert.throws(
		() =>
			validateSearchScope(
				searched,
				new Set([...goodSearch.consulted, "scm.pull-request.comments"]),
				new Set(goodSearch.consulted),
			),
		/^Error: answers\.one_concern rests on an absence, and this review did not stage scm\.pull-request\.comments, which the practice must search for one: an absence there cannot be shown\. Answer from lines you can cite, or answer UNDETERMINED with wouldSettleIt naming what was not staged$/u,
	);
});

void test("an exhaustive source the brief shows whole counts as searched, and is said so; one it does not show still owes a search", () => {
	const available = new Set(["scm.review-threads", "scm.issue.core", "scm.linked-work-items"]);
	const notes: string[] = [];
	const searched = normalize(answering({ search: goodSearch }));
	validateSearchScope(
		searched,
		new Set(["scm.issue.core", "scm.review-threads"]),
		available,
		new Set(["scm.issue.core"]),
		notes,
	);
	assert.deepEqual(searched.answers[0]?.search?.consulted, [
		"scm.issue.core",
		"scm.review-threads",
	]);
	assert.deepEqual(notes, [
		"answers.one_concern: scm.issue.core counted as searched — the brief shows it whole",
	]);
	// Only what the brief showed is counted: the source it did not show is refused as before.
	assert.throws(
		() =>
			validateSearchScope(
				normalize(answering({ search: goodSearch })),
				available,
				available,
				new Set(["scm.issue.core"]),
			),
		/^Error: answers\.one_concern rests on an absence in scm\.linked-work-items as well: add it to its search\.consulted once you have searched it$/u,
	);
	// A source the search already names is not counted again, and an answer with no search owes none.
	const already: string[] = [];
	validateSearchScope(
		normalize(answering({ search: goodSearch })),
		new Set(goodSearch.consulted),
		available,
		available,
		already,
	);
	validateSearchScope(normalize(observation()), available, available, available, already);
	assert.deepEqual(already, []);
});

void test("a search may only name sources this run staged", () => {
	const searched = normalize(answering({ search: goodSearch }));
	assert.throws(
		() => validateSearchScope(searched, new Set(), new Set()),
		/^Error: answers\.one_concern: searched source 'scm\.review-threads' was not available; copy one of these source kinds from the task-declared manifest: \(none\)$/u,
	);
	assert.throws(
		() =>
			validateSearchScope(
				searched,
				new Set(),
				new Set(["scm.issue.core", "scm.pull-request.diff"]),
			),
		/from the task-declared manifest: scm\.issue\.core, scm\.pull-request\.diff$/u,
	);
});

void test("a change of only binary files has no line to cite", () => {
	const binary =
		"diff --git a/diagrams/aom.png b/diagrams/aom.png\nnew file mode 100644\nindex 0000000..1111111\nBinary files /dev/null and b/diagrams/aom.png differ\n";
	assert.equal(changeHasCitableLines(binary), false);
	assert.equal(changeHasCitableLines(""), false);
	assert.equal(
		changeHasCitableLines(
			"diff --git a/src/A.ts b/src/A.ts\n--- a/src/A.ts\n+++ b/src/A.ts\n@@ -1,1 +1,1 @@\n[L1] -old\n[L1] +new\n",
		),
		true,
	);
});

void test("a range too long to record whole is recorded as its leading lines that fit, a single long line is not", () => {
	const line = `${"y".repeat(1500)}\n`;
	const text = line.repeat(4);
	const record = {
		sourceKind: "scm.pull-request.core",
		artifactPath: "inputs/context/description.md",
		path: "inputs/context/description.md",
		startLine: 1,
		endLine: 4,
		quote: "",
	};
	const shortened = resolveQuote(record, text);
	assert.ok(!("mismatch" in shortened));
	assert.equal(shortened.shortened, true);
	assert.equal(shortened.endLine, 2);
	assert.equal(shortened.quote, `${"y".repeat(1500)}\n${"y".repeat(1500)}`);
	// The change: the same holds for added lines of one file.
	const diff = `diff --git a/a.md b/a.md\n+++ b/a.md\n@@ -0,0 +1,4 @@\n${[1, 2, 3, 4].map((n) => `[L${n}] +${"z".repeat(1500)}`).join("\n")}\n`;
	const changed = resolveQuote(
		{ ...record, sourceKind: "scm.pull-request.diff", path: "a.md", side: "NEW" },
		diff,
	);
	assert.ok(!("mismatch" in changed));
	assert.equal(changed.shortened, true);
	assert.equal(changed.endLine, 2);
	assert.match(
		JSON.stringify(resolveQuote({ ...record, endLine: 1 }, `${"y".repeat(4001)}\n`)),
		/over the 4000 one entry may record/u,
	);
});
