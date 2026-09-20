import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import {
	ASSESSMENT_DESCRIPTIONS,
	ASSESSMENT_VALUES,
	citationMatchesArtifact,
	describeCitationMismatch,
	dedupeKeyForObservation,
	cellsRuledOut,
	describeVocabulary,
	MAX_SUMMARY_CHARS,
	type NormalizedCitation,
	normalizeObservation as normalizeFinalObservation,
	normalizeEvidence,
	PRESENCE_DESCRIPTIONS,
	PRESENCE_VALUES,
	type RecordedInapplicability,
	type RecordedSearch,
	SEVERITY_DESCRIPTIONS,
	SEVERITY_VALUES,
	validateEvidenceSources,
	validateInapplicabilityScope,
	validateSearchScope,
	resolveQuote,
	withoutCoordinates,
} from "../../../main/resources/agent/pi-observation-normalize.ts";

interface ObservationOverrides {
	practiceSlug?: unknown;
	title?: unknown;
	presence?: unknown;
	assessmentStatus?: unknown;
	assessment?: unknown;
	severity?: unknown;
	reasoning?: unknown;
	evidence?: EvidenceOverrides;
	[key: string]: unknown;
}
type EvidenceFixture = { citations: Record<string, unknown>[] } & Record<string, unknown>;
type EvidenceOverrides = Partial<EvidenceFixture> & Record<string, unknown>;
function baseObservation(overrides: ObservationOverrides = {}) {
	return {
		practiceSlug: "writes_focused_pull_requests",
		summary: "PR mixes unrelated changes",
		assessmentStatus: "ASSESSED",
		presence: "PRESENT",
		assessment: "BAD",
		severity: "MAJOR",
		evidenceRationale: "The diff touches auth and billing in one PR.",
		...overrides,
		evidence: {
			citations: overrides.evidence?.citations ?? [
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
			...overrides.evidence,
		},
	};
}
const normalizeObservation = normalizeFinalObservation;

function onlyCitation<T extends object>(citations: readonly T[]): T {
	const [citation] = citations;
	if (!citation) throw new Error("expected the observation to carry exactly one citation");
	return citation;
}

const UNDECIDABLE = {
	openQuestion: "Whether the body states a why, or only restates the title",
	wouldSettleIt: "Clarification of the contradictory acceptance requirements",
};

void test("lowercase enums + underscored slug normalize and are accepted (not dropped)", () => {
	const out = normalizeObservation(baseObservation());
	assert.equal(out.practiceSlug, "writes-focused-pull-requests");
	assert.equal(out.presence, "PRESENT");
	assert.equal(out.assessment, "BAD");
	assert.equal(out.severity, "MAJOR");
});

void test("a line-number refusal names what was received, and an omitted line as omitted", () => {
	const cited = (lines: Record<string, unknown>) =>
		baseObservation({
			evidence: {
				citations: [
					{
						sourceKind: "scm.pull-request.diff",
						artifactPath: "inputs/context/diff.patch",
						path: "src/Auth.java",
						side: "NEW",
						quote: "+ insecure();",
						...lines,
					},
				],
			},
		});
	assert.throws(() => normalizeObservation(cited({})), /startLine is required: the 1-based line/);
	assert.throws(() => normalizeObservation(cited({ startLine: null })), /startLine is required/);
	assert.throws(
		() => normalizeObservation(cited({ startLine: 0 })),
		/startLine must be a positive integer, received 0; lines are 1-based/,
	);
	assert.throws(
		() => normalizeObservation(cited({ startLine: "ten" })),
		/received "ten"; lines are 1-based/,
	);
	assert.throws(
		() => normalizeObservation(cited({ startLine: 10, endLine: 4 })),
		/endLine must be an integer >= startLine, received 4 with startLine 10/,
	);
});

void test("an item with no practiceSlug is refused as not an observation, before its cell is read", () => {
	assert.throws(
		() => normalizeObservation({ summary: "PR mixes unrelated changes" }),
		/practiceSlug is required: each item of observations is one observation object \(received keys: summary\)/,
	);
	assert.throws(() => normalizeObservation({}), /received keys: none/);
});

void test("mixed-case enums up-case", () => {
	const out = normalizeObservation(
		baseObservation({ presence: "Present", assessment: "Good", severity: null }),
	);
	assert.equal(out.presence, "PRESENT");
	assert.equal(out.assessment, "GOOD");
	assert.equal(out.severity, null);
});

void test("an observation carries no confidence, and one offered is rejected", () => {
	const out = normalizeObservation(baseObservation());
	assert.equal("confidence" in out, false);
	for (const confidence of [-1, 4200, "very", null]) {
		assert.throws(
			() => normalizeObservation(baseObservation({ confidence })),
			/unknown observation field.*confidence/,
		);
	}
});

void test("NOT_APPLICABLE carries explicit null axes", () => {
	const out = normalizeObservation(notApplicableObservation(goodInapplicability));
	assert.equal(out.assessmentStatus, "NOT_APPLICABLE");
	assert.equal(out.presence, null);
	assert.equal(out.assessment, null);
	assert.equal(out.severity, null);
});

void test("dedupe key uses the normalized hyphenated slug", () => {
	const a = dedupeKeyForObservation(
		normalizeObservation(baseObservation({ practiceSlug: "writes_focused_pull_requests" })),
	);
	const b = dedupeKeyForObservation(
		normalizeObservation(baseObservation({ practiceSlug: "WRITES-FOCUSED-PULL-REQUESTS" })),
	);
	assert.equal(a, b, "underscored and upper-hyphenated slugs must dedupe to the same key");
});

void test("a one-word summary is refused, because it names nothing on the practice page", () => {
	assert.throws(() => normalizeObservation(baseObservation({ summary: "Test" })), /short phrase/);
	assert.throws(
		() => normalizeObservation(baseObservation({ summary: "  Duplication  " })),
		/short phrase/,
	);
	assert.equal(normalizeObservation(baseObservation({ summary: "No tests" })).summary, "No tests");
});

void test("a field left out, or written as the word null, reads as null", () => {
	const notApplicable = {
		assessmentStatus: "NOT_APPLICABLE",
		evidence: {
			citations: baseObservation().evidence.citations,
			inapplicability: {
				consulted: ["scm.pull-request.diff"],
				subject: "settings",
				ruledOutBy: "no code",
			},
		},
	};
	assert.equal(
		normalizeObservation(
			baseObservation({ ...notApplicable, presence: "null", assessment: "None", severity: "" }),
		).presence,
		null,
	);
	const { presence: _p, assessment: _a, severity: _s, ...omitted } = baseObservation(notApplicable);
	assert.equal(normalizeObservation(omitted).assessmentStatus, "NOT_APPLICABLE");
	assert.throws(
		() => normalizeObservation(baseObservation({ severity: "null" })),
		/PRESENT\/BAD is a NEGATIVE outcome and needs a severity: one of CRITICAL, MAJOR, MINOR, INFO/,
	);
});

void test("a summary longer than the practice page shows is kept up to a sentence end, else refused", () => {
	const long = "The handler swallows the error ".repeat(6).trim();
	assert.ok(long.length > MAX_SUMMARY_CHARS);
	assert.throws(
		() => normalizeObservation(baseObservation({ summary: long })),
		new RegExp(
			`at most ${MAX_SUMMARY_CHARS} characters; this one is ${long.length} with no sentence end inside the bound`,
		),
	);
	const atTheLimit = "x ".repeat(MAX_SUMMARY_CHARS / 2).trim();
	assert.equal(normalizeObservation(baseObservation({ summary: atTheLimit })).summary, atTheLimit);
	// A clause too long, with a sentence end inside the bound: kept up to it, and the session is told.
	const twoSentences = `The handler swallows the error. ${"It is caught and logged at debug level ".repeat(4).trim()}`;
	assert.ok(twoSentences.length > MAX_SUMMARY_CHARS);
	const notes: string[] = [];
	const shortened = normalizeObservation(
		baseObservation({ summary: twoSentences }),
		new Set(),
		notes,
	);
	assert.equal(shortened.summary, "The handler swallows the error.");
	assert.deepEqual(notes, [
		`summary was ${twoSentences.length} characters; recorded up to its last sentence end within ${MAX_SUMMARY_CHARS}: "The handler swallows the error."`,
	]);
	// Runs of whitespace are one space: a summary is one line on the page.
	assert.equal(
		normalizeObservation(baseObservation({ summary: "PR mixes\n  unrelated   changes" })).summary,
		"PR mixes unrelated changes",
	);
});

void test("a field sent beside the observation instead of under evidence is read from there and named", () => {
	const { evidence, ...rest } = baseObservation();
	const notes: string[] = [];
	// citations beside the observation, and the rationale under evidence: each moved to its home.
	const { evidenceRationale, ...withoutRationale } = rest;
	const rehomed = normalizeObservation(
		{
			...withoutRationale,
			citations: evidence.citations,
			evidence: { evidenceRationale },
		},
		new Set(),
		notes,
	);
	assert.equal(rehomed.evidenceRationale, evidenceRationale);
	assert.equal(rehomed.evidence.citations.length, 1);
	assert.deepEqual(notes, [
		"evidenceRationale read from under evidence; it belongs beside evidence, not in it",
		"citations read from beside the observation; they belong under evidence",
	]);
	// The search fields beside an ABSENT observation, with no search wrapper at all.
	const absent = normalizeObservation(
		{ ...rest, presence: "ABSENT", assessment: "BAD", evidence, ...goodSearch },
		new Set(),
		notes,
	);
	assert.deepEqual(absent.evidence.search?.consulted, ["scm.review-threads"]);
	assert.equal(
		notes.at(-1),
		"search{consulted, lookedFor, boundary} read from beside the observation; they belong under evidence",
	);
	// A field present in both places is not guessed at: the unknown-field check names it.
	assert.throws(
		() => normalizeObservation({ ...rest, evidence, citations: [] }),
		/unknown observation field\(s\): citations/,
	);
});

void test("genuinely invalid enum still rejected after normalization", () => {
	const invalid = baseObservation();
	invalid.presence = "MAYBE";
	assert.throws(
		() => normalizeObservation(invalid),
		/invalid presence 'MAYBE': one of PRESENT, ABSENT/,
	);
	// A word of the vocabulary in another spelling is that word; a missing one is named as missing.
	assert.equal(
		normalizeObservation(
			baseObservation({
				assessmentStatus: "not applicable",
				presence: null,
				assessment: null,
				severity: null,
				evidence: {
					citations: baseObservation().evidence.citations,
					inapplicability: {
						consulted: ["scm.pull-request.diff"],
						subject: "tests",
						ruledOutBy: "docs only",
					},
				},
			}),
		).assessmentStatus,
		"NOT_APPLICABLE",
	);
	assert.throws(
		() => normalizeObservation(baseObservation({ presence: undefined })),
		/invalid presence 'undefined' \(missing\): one of PRESENT, ABSENT/,
	);
});

void test("missing evidence-source attribution is rejected", () => {
	assert.throws(
		() => normalizeObservation(baseObservation({ evidence: { citations: [] } })),
		/citations are required/,
	);
});

void test("citation requires an exact artifact path and quote", () => {
	const missingPath = baseObservation();
	delete onlyCitation(missingPath.evidence.citations).artifactPath;
	assert.throws(() => normalizeObservation(missingPath), /artifactPath is required/);

	// No quote is a citation by coordinates alone; the runner fills it from the artifact.
	const missingQuote = baseObservation();
	delete onlyCitation(missingQuote.evidence.citations).quote;
	assert.equal(onlyCitation(normalizeObservation(missingQuote).evidence.citations).quote, "");
});

void test("citation side is present exactly for pull-request diffs", () => {
	// A diff citation may leave the side out; the runner records the side the text is found on.
	const missingDiffSide = baseObservation();
	delete onlyCitation(missingDiffSide.evidence.citations).side;
	assert.equal(
		"side" in onlyCitation(normalizeObservation(missingDiffSide).evidence.citations),
		false,
	);
	const wrongSide = baseObservation();
	onlyCitation(wrongSide.evidence.citations).side = "BOTH";
	assert.throws(() => normalizeObservation(wrongSide), /side must be OLD or NEW/);

	// A side on anything but a quote of the change says nothing: surplus, dropped rather than refused.
	const nonDiffSide = baseObservation();
	onlyCitation(nonDiffSide.evidence.citations).sourceKind = "scm.pull-request.core";
	assert.equal("side" in onlyCitation(normalizeObservation(nonDiffSide).evidence.citations), false);
});

void test("a citation must name a source this run staged, and the artifact that source produced", () => {
	const observation = normalizeObservation(baseObservation());
	assert.doesNotThrow(() =>
		validateEvidenceSources(
			observation,
			new Set(["scm.pull-request.diff"]),
			new Map([["inputs/context/diff.patch", "scm.pull-request.diff"]]),
		),
	);
	assert.doesNotThrow(() =>
		validateEvidenceSources(
			observation,
			new Set(["scm.pull-request.diff", "workspace.project-inventory"]),
			new Map([["inputs/context/diff.patch", "scm.pull-request.diff"]]),
		),
	);
	assert.throws(
		() =>
			validateEvidenceSources(
				observation,
				new Set(["scm.pull-request.core", "scm.review-threads"]),
				new Map(),
			),
		/was not available.*scm\.pull-request\.core, scm\.review-threads/,
	);
	assert.throws(
		() =>
			validateEvidenceSources(
				observation,
				new Set(["scm.pull-request.diff"]),
				new Map([["inputs/context/diff.patch", "scm.pull-request.core"]]),
			),
		/belongs to evidence source 'scm\.pull-request\.core', not 'scm\.pull-request\.diff'/,
	);
	assert.throws(
		() =>
			validateEvidenceSources(
				observation,
				new Set(["scm.pull-request.diff"]),
				new Map([["inputs/context/change.json", "scm.pull-request.diff"]]),
			),
		/was not staged; the staged artifacts are: inputs\/context\/change\.json\.$/,
	);
	// The change view is derived in the container; a citation of it is told what the artifact is.
	const derived = normalizeObservation(baseObservation());
	onlyCitation(derived.evidence.citations).artifactPath = "work/change/files.json";
	assert.throws(
		() => validateEvidenceSources(derived, new Set(["scm.pull-request.diff"]), new Map()),
		/work\/ is derived here and is not an artifact: quote a changed line from work\/change\/diff\.patch/,
	);
});

void test("diff citations bind the quote to the claimed file and side; a wrong line is corrected when the text is unique", () => {
	const citation = onlyCitation(normalizeObservation(baseObservation()).evidence.citations);
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
	const citation = onlyCitation(normalizeObservation(baseObservation()).evidence.citations);
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
		/\[L10] reads/,
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

void test("a matching diff quote is recorded as the content its lines carry, markers dropped", () => {
	const citation = onlyCitation(normalizeObservation(baseObservation()).evidence.citations);
	const diff =
		"diff --git a/run.sh b/run.sh\n+++ b/run.sh\n@@ -10,2 +10,2 @@\n[L10] +    -flag --now\n[L11] +  next\n";
	const cited = { ...citation, path: "run.sh", endLine: 11 };
	const mismatch = (result: ReturnType<typeof resolveQuote>) =>
		"mismatch" in result ? result.mismatch : "";
	// Admission reads the blob at the revision, where no marker exists; so the quote must not carry one.
	assert.deepEqual(resolveQuote({ ...cited, quote: "+    -flag --now\n+  next" }, diff), {
		quote: "    -flag --now\n  next",
	});
	assert.deepEqual(resolveQuote({ ...cited, quote: "    -flag --now\n  next" }, diff), {
		quote: "    -flag --now\n  next",
	});
	// Coordinates alone record the lines.
	assert.deepEqual(resolveQuote({ ...cited, quote: "" }, diff), {
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
		/cited lines are blank/,
	);
	assert.match(mismatch(resolveQuote({ ...cited, quote: "", endLine: 12 }, diff)), /no \[L12\]/);
	// What does not match is refused with what the line reads.
	assert.match(
		mismatch(resolveQuote({ ...cited, quote: "+    -flag\n+  next" }, diff)),
		/\[L10\] reads/,
	);
});

void test("a quote is recorded as the artifact spells it: JSON escapes and non-breaking spaces", () => {
	const { side: _side, ...plain } = onlyCitation(
		normalizeObservation(baseObservation()).evidence.citations,
	);
	const citation = { ...plain, sourceKind: "scm.linked-work-items", startLine: 2, endLine: 2 };
	const mismatch = (result: ReturnType<typeof resolveQuote>) =>
		"mismatch" in result ? result.mismatch : "";
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
	const nbsp = "* Launch app and open\u00a0`Venues`.\n";
	const first = { ...citation, startLine: 1, endLine: 1 };
	assert.deepEqual(resolveQuote({ ...first, quote: "* Launch app and open `Venues`." }, nbsp), {
		quote: "* Launch app and open\u00a0`Venues`.",
	});
	// A blank line read with a stray space, and non-breaking spaces read as spaces, across lines.
	const steps = "## Testing\n\n1. Launch app and open\u00a0**Coupons**\u00a0tab.\n2. Verify.\n";
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
		{ quote: "## Testing\n\n1. Launch app and open\u00a0**Coupons**\u00a0tab.\n2. Verify." },
	);
	// Coordinates alone record the line, up to a bound.
	assert.deepEqual(resolveQuote({ ...first, quote: "" }, nbsp), {
		quote: "* Launch app and open\u00a0`Venues`.",
	});
	assert.match(
		mismatch(resolveQuote({ ...first, quote: "" }, `${"x".repeat(2001)}\n`)),
		/cite fewer lines or quote a fragment/,
	);
	// A quote across the body's line breaks is a reading of the escaped form and resolves to it.
	assert.deepEqual(resolveQuote({ ...citation, quote: "## Criteria\n- [x] stored" }, serialized), {
		quote: "## Criteria\\n- [x] stored",
	});
});

void test("a quote from the other side of the change is refused, however it is written", () => {
	const citation: NormalizedCitation = {
		...onlyCitation(normalizeObservation(baseObservation()).evidence.citations),
		side: "NEW",
		startLine: 47,
		endLine: 47,
		quote: '-@RequestMapping({ "api/legacy/" })',
	};
	const diff =
		"--- a/src/Auth.java\n+++ b/src/Auth.java\n@@ -47 +47 @@\n" +
		'[L47] -@RequestMapping({ "api/legacy/" })\n[L47] +@RequestMapping("api/passkeys/")\n';

	assert.match(describeCitationMismatch(citation, diff) ?? "", /\[L47] reads/);
});

void test("a quote may carry its exact displayed coordinate", () => {
	const citation = onlyCitation(normalizeObservation(baseObservation()).evidence.citations);
	const diff =
		"diff --git a/src/Auth.java b/src/Auth.java\n+++ b/src/Auth.java\n@@ -10 +10 @@\n[L10] + insecure();\n";

	assert.equal(describeCitationMismatch({ ...citation, quote: "[L10] + insecure();" }, diff), null);
	// The coordinate still has to be the one being matched, so a quote cannot claim a line it did
	// not read — even when that line's text is in the diff somewhere else.
	assert.match(
		describeCitationMismatch({ ...citation, quote: "[L11] + insecure();" }, diff) ?? "",
		/\[L10] reads/,
	);
});

void test("a refused citation says which of the coordinate, the side and the text was wrong", () => {
	const citation = onlyCitation(normalizeObservation(baseObservation()).evidence.citations);
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
		/no \[L11] on that side of that path/,
	);
	// The coordinate is there and says something else, so the refusal shows both.
	assert.match(
		describeCitationMismatch({ ...citation, quote: "+ secure();" }, diff) ?? "",
		/\[L10] reads "\+ insecure\(\);", not "\+ secure\(\);"/,
	);
	// The quote and the line span disagree, but the block occurs once: recorded where it is.
	assert.deepEqual(resolveQuote({ ...citation, endLine: 12 }, diff), {
		quote: " insecure();",
		startLine: 10,
		endLine: 10,
	});
	assert.match(
		describeCitationMismatch({ ...citation, endLine: 12, quote: "gone();\nalso();" }, diff) ?? "",
		/quote is 2 line\(s\) and the citation covers 3/,
	);
});

void test("removed-line citations use old-side coordinates", () => {
	const citation: NormalizedCitation = {
		...onlyCitation(normalizeObservation(baseObservation()).evidence.citations),
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

void test("UNDETERMINED is accepted and carries no assessment", () => {
	const out = normalizeObservation({
		...baseObservation(),
		assessmentStatus: "UNDETERMINED",
		presence: null,
		assessment: null,
		severity: null,
		evidence: { ...baseObservation().evidence, undecidability: UNDECIDABLE },
	});
	assert.equal(out.assessmentStatus, "UNDETERMINED");
	assert.equal(out.presence, null);
	assert.equal(out.assessment, null);
});

void test("contradictory axes are rejected, not silently corrected", () => {
	for (const assessmentStatus of ["NOT_APPLICABLE", "UNDETERMINED"]) {
		assert.throws(
			() => normalizeObservation(baseObservation({ assessmentStatus })),
			/explicit null/,
		);
	}
	// A severity beside a POSITIVE outcome is surplus, dropped rather than refused; a NEGATIVE one
	// without a severity is a contradiction.
	assert.equal(normalizeObservation(baseObservation({ assessment: "GOOD" })).severity, null);
	assert.throws(
		() => normalizeObservation(baseObservation({ severity: null })),
		/PRESENT\/BAD is a NEGATIVE outcome and needs a severity/,
	);
	assert.throws(
		() => normalizeObservation(baseObservation({ presence: null })),
		/invalid presence/,
	);
});

function absentObservation(
	search: Partial<RecordedSearch> | undefined,
	overrides: ObservationOverrides = {},
) {
	return {
		...baseObservation(),
		presence: "ABSENT",
		assessment: "GOOD",
		evidence: { ...baseObservation().evidence, ...(search === undefined ? {} : { search }) },
		...overrides,
	};
}

const goodSearch = {
	consulted: ["scm.review-threads"],
	lookedFor: "a review thread raising the migration",
	boundary: "only threads on this pull request; nothing in chat",
};

void test("an ABSENT observation must record where it searched", () => {
	assert.throws(() => normalizeObservation(absentObservation(undefined)), /must record its search/);
	assert.throws(
		() => normalizeObservation(absentObservation({ ...goodSearch, consulted: [] })),
		/at least one source/,
	);
	assert.throws(
		() => normalizeObservation(absentObservation({ ...goodSearch, lookedFor: " " })),
		/lookedFor is required/,
	);
	assert.throws(
		() => normalizeObservation(absentObservation({ ...goodSearch, boundary: "" })),
		/boundary is required/,
	);

	const out = normalizeObservation(absentObservation(goodSearch));
	assert.deepEqual(out.evidence.search?.consulted, ["scm.review-threads"]);
});

void test("a search recorded beside a non-ABSENT claim is surplus and dropped, not refused", () => {
	assert.doesNotThrow(() => normalizeObservation(baseObservation()));
	assert.equal("search" in normalizeObservation(baseObservation()).evidence, false);
	const withSurplus = normalizeObservation({
		...baseObservation(),
		evidence: { ...baseObservation().evidence, search: goodSearch },
	});
	assert.equal("search" in withSurplus.evidence, false);
	assert.equal(withSurplus.evidence.citations.length, 1);
});

void test("ABSENT is refused unless the search covered every source the practice asserts absence over", () => {
	const observation = normalizeObservation(absentObservation(goodSearch));
	const available = new Set(["scm.review-threads", "scm.linked-work-items"]);

	assert.doesNotThrow(() =>
		validateSearchScope(observation, new Set(["scm.review-threads"]), available),
	);
	assert.throws(
		() =>
			validateSearchScope(
				observation,
				new Set(["scm.review-threads", "scm.linked-work-items"]),
				available,
			),
		/without searching scm.linked-work-items/,
	);
	assert.throws(
		() => validateSearchScope(observation, new Set(), new Set(["scm.pull-request.diff"])),
		/was not available.*scm\.pull-request\.diff/,
	);
});

void test("Positive absence needs a bounded corpus; missing desirable behaviour does not", () => {
	const strength = normalizeObservation(
		absentObservation(goodSearch, { assessment: "BAD", severity: null }),
	);
	const gap = normalizeObservation(absentObservation(goodSearch));
	const available = new Set(["scm.review-threads"]);

	assert.doesNotThrow(() =>
		validateSearchScope(strength, new Set(["scm.review-threads"]), available),
	);
	assert.throws(() => validateSearchScope(strength, new Set(), available), /ABSENT \+ BAD/);
	assert.throws(() => validateSearchScope(strength, new Set(), available), /UNDETERMINED/);
	assert.doesNotThrow(() => validateSearchScope(gap, new Set(), available));
});

void test("a bounded corpus does not excuse a partial search, in either direction", () => {
	const strength = normalizeObservation(
		absentObservation(goodSearch, { assessment: "BAD", severity: null }),
	);
	const available = new Set(["scm.review-threads", "scm.linked-work-items"]);
	assert.throws(
		() =>
			validateSearchScope(
				strength,
				new Set(["scm.review-threads", "scm.linked-work-items"]),
				available,
			),
		/without searching scm.linked-work-items/,
	);
});

void test("the search scope rule applies to ABSENT only", () => {
	const present = normalizeObservation(baseObservation());
	assert.doesNotThrow(() =>
		validateSearchScope(present, new Set(["scm.review-threads"]), new Set()),
	);
});

void test("a claim about an earlier review is bound to the staged history like any other citation", () => {
	const observation = normalizeObservation(
		baseObservation({
			evidence: {
				citations: [
					{
						sourceKind: "hephaestus.observation-history",
						artifactPath: "inputs/history/observations.json",
						path: "inputs/history/observations.json",
						startLine: 1,
						endLine: 1,
						quote: '"recurrenceKey": "rec-1"',
					},
				],
			},
		}),
	);
	const artifacts = new Map([
		["inputs/history/observations.json", "hephaestus.observation-history"],
	]);
	const staged = new Set(["scm.pull-request.diff", "hephaestus.observation-history"]);

	assert.doesNotThrow(() => validateEvidenceSources(observation, staged, artifacts));
	const bytes = '{"observations":[{"recurrenceKey": "rec-1","title":"Caught and ignored"}]}';
	assert.equal(citationMatchesArtifact(onlyCitation(observation.evidence.citations), bytes), true);
	const invented = {
		...onlyCitation(observation.evidence.citations),
		quote: '"recurrenceKey": "invented"',
	};
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
		/^\[L1\] reads .*; this is a JSON string whose line breaks are the two characters \\n, so quote a fragment from between two of them, or spell them as the line does$/,
	);
	assert.equal(
		describeCitationMismatch({ ...invented, quote: "- [x] stored in `diagrams/`" }, serialized),
		null,
	);
});

void test("the history is never an exhaustive source, so it can never carry an absence", () => {
	const observation = normalizeObservation(
		absentObservation({
			consulted: ["scm.review-threads", "hephaestus.observation-history"],
			lookedFor: "a review thread raising the migration",
			boundary: "threads on this pull request, plus the earlier record for this person",
		}),
	);
	const staged = new Set(["scm.review-threads", "hephaestus.observation-history"]);

	assert.doesNotThrow(() =>
		validateSearchScope(observation, new Set(["scm.review-threads"]), staged),
	);
});

function notApplicableObservation(
	inapplicability: Partial<RecordedInapplicability> | undefined,
	overrides: ObservationOverrides = {},
) {
	const base = baseObservation();
	return {
		...base,
		assessmentStatus: "NOT_APPLICABLE",
		presence: null,
		assessment: null,
		severity: null,
		evidence: {
			...base.evidence,
			...(inapplicability === undefined ? {} : { inapplicability }),
		},
		...overrides,
	};
}

const goodInapplicability = {
	consulted: ["scm.pull-request.diff"],
	subject: "error handling around outbound network calls",
	ruledOutBy: "the change touches only Markdown documentation and makes no network calls",
};

void test("a NOT_APPLICABLE observation must say what rules the practice out", () => {
	assert.throws(
		() => normalizeObservation(notApplicableObservation(undefined)),
		/must say why the practice does not apply/,
	);
	assert.throws(
		() => normalizeObservation(notApplicableObservation({ ...goodInapplicability, consulted: [] })),
		/at least one source/,
	);
	assert.throws(
		() => normalizeObservation(notApplicableObservation({ ...goodInapplicability, subject: " " })),
		/subject is required/,
	);
	assert.throws(
		() =>
			normalizeObservation(notApplicableObservation({ ...goodInapplicability, ruledOutBy: "" })),
		/ruledOutBy is required/,
	);

	const out = normalizeObservation(notApplicableObservation(goodInapplicability));
	const { inapplicability } = out.evidence;
	assert.ok(inapplicability, "a NOT_APPLICABLE observation must come back carrying its ground");
	assert.deepEqual(inapplicability.consulted, ["scm.pull-request.diff"]);
	assert.equal(inapplicability.subject, goodInapplicability.subject);
});

void test("the refusal points at UNDETERMINED, because that is the answer it is asking for", () => {
	assert.throws(() => normalizeObservation(notApplicableObservation(undefined)), /inapplicability/);
	assert.throws(
		() =>
			normalizeObservation(notApplicableObservation({ ...goodInapplicability, ruledOutBy: "" })),
		/ruledOutBy/,
	);
});

void test("UNDETERMINED needs no inapplicability block — it is not claiming anything about the work", () => {
	const out = normalizeObservation({
		...baseObservation(),
		assessmentStatus: "UNDETERMINED",
		presence: null,
		assessment: null,
		severity: null,
		evidence: { ...baseObservation().evidence, undecidability: UNDECIDABLE },
	});
	assert.equal("inapplicability" in out.evidence, false);
});

void test("a NOT_APPLICABLE claim may only rest on sources this run staged", () => {
	const observation = normalizeObservation(notApplicableObservation(goodInapplicability));
	assert.doesNotThrow(() =>
		validateInapplicabilityScope(observation, new Set(["scm.pull-request.diff"])),
	);
	assert.throws(
		() => validateInapplicabilityScope(observation, new Set(["scm.review-threads"])),
		/was not available.*scm\.review-threads/,
	);

	const present = normalizeObservation(baseObservation());
	assert.doesNotThrow(() => validateInapplicabilityScope(present, new Set()));
});

void test("removed measurement fields are rejected rather than silently accepted", () => {
	assert.throws(
		() => normalizeObservation(baseObservation({ guidance: "Split into two PRs." })),
		/unknown observation field.*guidance/,
	);
	assert.throws(
		() => normalizeObservation(baseObservation({ suggestedDiffNotes: [] })),
		/unknown observation field.*suggestedDiffNotes/,
	);
});

void test("all assessed combinations preserve the specified behavior and judgment", () => {
	for (const presence of PRESENCE_VALUES) {
		for (const assessment of ASSESSMENT_VALUES) {
			const severity = (presence === "PRESENT") !== (assessment === "GOOD") ? "MAJOR" : null;
			const observation = baseObservation({ presence, assessment, severity });
			const evidence = {
				...observation.evidence,
				...(presence === "ABSENT" ? { search: goodSearch } : {}),
			};
			const out = normalizeFinalObservation({ ...observation, evidence });
			assert.equal(out.assessmentStatus, "ASSESSED");
			assert.equal(out.presence, presence);
			assert.equal(out.assessment, assessment);
			assert.equal(out.severity, severity);
		}
	}
});

void test("legacy combined outcomes and omitted status are rejected", () => {
	assert.throws(
		() =>
			normalizeFinalObservation({ ...baseObservation(), outcome: "BEHAVIOR_PRESENT_BAD_MAJOR" }),
		/unknown observation field/,
	);
	assert.throws(
		() => normalizeFinalObservation({ ...baseObservation(), assessmentStatus: undefined }),
		/invalid assessmentStatus/,
	);
});

void test("every vocabulary value carries a description", () => {
	const vocabularies: { values: readonly string[]; descriptions: object; label: string }[] = [
		{ values: PRESENCE_VALUES, descriptions: PRESENCE_DESCRIPTIONS, label: "presence" },
		{ values: ASSESSMENT_VALUES, descriptions: ASSESSMENT_DESCRIPTIONS, label: "assessment" },
		{ values: SEVERITY_VALUES, descriptions: SEVERITY_DESCRIPTIONS, label: "severity" },
	];
	for (const { values, descriptions, label } of vocabularies) {
		assert.deepEqual(
			Object.keys(descriptions).toSorted(),
			[...values].toSorted(),
			`${label} descriptions must cover exactly ${label} values`,
		);
	}
});

void test("describeVocabulary refuses a value it cannot describe", () => {
	const unpromising: Record<string, string> = { ...PRESENCE_DESCRIPTIONS };
	assert.throws(
		() => describeVocabulary([...PRESENCE_VALUES, "UNDECIDED"], unpromising),
		/'UNDECIDED' has no description/,
	);
});

void test("a citation rejects typographic substitutions not present in the artifact", () => {
	const content = 'Resolve "Connect data between screens" — see the plan';
	const cite = (quote: string): NormalizedCitation => ({
		sourceKind: "scm.pull-request.core",
		artifactPath: "inputs/context/core.md",
		path: "title",
		startLine: 1,
		endLine: 1,
		quote,
	});

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
	const cite = (quote: string): NormalizedCitation => ({
		sourceKind: "scm.pull-request.core",
		artifactPath: "inputs/context/core.md",
		path: "title",
		startLine: 1,
		endLine: 1,
		quote,
	});

	assert.equal(
		citationMatchesArtifact(cite("Resolve “Disconnect data between screens”"), content),
		false,
	);
	assert.equal(citationMatchesArtifact(cite("a rationale the author never wrote"), content), false);
});

void test("an UNDETERMINED observation must say what it could not settle", () => {
	const base = {
		practiceSlug: "describe-what-and-why",
		summary: "Acceptance requirements contradict one another",
		assessment: null,
		assessmentStatus: "UNDETERMINED",
		presence: null,

		severity: null,
		evidenceRationale:
			"The captured requirements state incompatible expected results for the same input.",
		evidence: { citations: baseObservation().evidence.citations },
	};

	assert.throws(() => normalizeObservation(base), /undecidability/);
	assert.throws(
		() =>
			normalizeObservation({
				...base,
				evidence: { ...base.evidence, undecidability: { openQuestion: "x" } },
			}),
		/wouldSettleIt/,
	);

	const ok = normalizeObservation({
		...base,
		evidence: {
			...base.evidence,
			undecidability: {
				openQuestion: "Whether the body states a why",
				wouldSettleIt: "The linked issue's body",
			},
		},
	});
	assert.equal(ok.assessmentStatus, "UNDETERMINED");
	assert.equal(ok.assessment, null);
	assert.equal(ok.evidence.undecidability?.wouldSettleIt, "The linked issue's body");
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
	assert.equal(
		normalizeEvidence({ citations: [citation] }, "ASSESSED", "PRESENT").citations[0]?.revision,
		citation.revision,
	);
	assert.throws(
		() =>
			normalizeEvidence(
				{ citations: [{ ...citation, revision: "HEAD~1" }] },
				"ASSESSED",
				"PRESENT",
			),
		/full commit SHA/,
	);
	assert.throws(
		() =>
			normalizeEvidence(
				{ citations: [{ ...citation, sourceKind: "scm.issue.core" }] },
				"ASSESSED",
				"PRESENT",
			),
		/scm.repository.tree/,
	);
});

void test("live practice fixture permits a citation of its planted credential", () => {
	const diff = readFileSync(new URL("./live-practice/diff.patch", import.meta.url), "utf8");
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
	const raw = baseObservation();
	const quote = "    insecure();  ";
	onlyCitation(raw.evidence.citations).quote = quote;
	assert.equal(onlyCitation(normalizeObservation(raw).evidence.citations).quote, quote);
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
		/it occurs at \[L2\], \[L4\] — cite the one you mean/,
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
		const raw = baseObservation();
		onlyCitation(raw.evidence.citations).quote = quote;
		const citation = onlyCitation(normalizeObservation(raw).evidence.citations);
		assert.equal(citation.quote, quote);
		assert.equal(describeCitationMismatch(citation, diff), null);
	}
	const raw = baseObservation();
	onlyCitation(raw.evidence.citations).quote = "  Trivio:  # TODO: Adjust Name\r\n\r\n";
	onlyCitation(raw.evidence.citations).endLine = 11;
	const citation = onlyCitation(normalizeObservation(raw).evidence.citations);
	assert.equal(citation.quote, "  Trivio:  # TODO: Adjust Name\r\n\r\n");
	assert.equal(describeCitationMismatch(citation, diff), null);
});

void test("citation coordinates are bounded and control-only quotes are blank", () => {
	for (const line of [2147483648, 4294967306, Number.MAX_SAFE_INTEGER + 1]) {
		const raw = baseObservation();
		onlyCitation(raw.evidence.citations).startLine = line;
		assert.throws(() => normalizeObservation(raw), /startLine/);
		onlyCitation(raw.evidence.citations).startLine = 10;
		onlyCitation(raw.evidence.citations).endLine = line;
		assert.throws(() => normalizeObservation(raw), /endLine/);
	}
});

void test("annotated source text is not parsed as a header and Unicode separators remain source text", () => {
	const cite = onlyCitation(normalizeObservation(baseObservation()).evidence.citations);
	const diff =
		"--- a/src/Auth.java\n+++ b/src/Auth.java\n[L10] --- SQL comment\n[L10] +++ value\u2028tail\u2029end\n";
	assert.equal(
		describeCitationMismatch({ ...cite, side: "OLD", quote: "-- SQL comment" }, diff),
		null,
	);
	assert.equal(
		describeCitationMismatch({ ...cite, quote: "++ value\u2028tail\u2029end" }, diff),
		null,
	);
	assert.notEqual(
		describeCitationMismatch(
			{ ...cite, endLine: 11, quote: "x\n\n" },
			"--- a/src/Auth.java\n+++ b/src/Auth.java\n[L10] +x\n[L11] ",
		),
		null,
	);
	assert.equal(
		describeCitationMismatch({ ...cite, path: '"', quote: "x" }, '--- "\n+++ "\n[L10] +x\n'),
		null,
	);
});

void test("normalization preserves nonblank citation path identifiers", () => {
	const raw = baseObservation();
	const supplied = onlyCitation(raw.evidence.citations);
	supplied.path = " source file ";
	supplied.artifactPath = "inputs/context/ captured file ";
	const citation = onlyCitation(normalizeObservation(raw).evidence.citations);
	assert.equal(citation.path, supplied.path);
	assert.equal(citation.artifactPath, supplied.artifactPath);
	for (const field of ["path", "artifactPath"]) {
		const blank = baseObservation();
		onlyCitation(blank.evidence.citations)[field] = " \t\n";
		assert.throws(() => normalizeObservation(blank), new RegExp(`${field} is required`));
	}
});

void test("a quote copied with the brief's line coordinates is stored without them", () => {
	assert.equal(withoutCoordinates("[L15] to test the winner", 15), "to test the winner");
	assert.equal(withoutCoordinates("[L15] first\n[L16] second\n", 15), "first\nsecond\n");
	// A coordinate that names another line is text, and stays.
	assert.equal(withoutCoordinates("[L9] elsewhere", 15), "[L9] elsewhere");
	assert.equal(withoutCoordinates("plain", 3), "plain");
});

void test("the pinned change file is not a quotable artifact; the refusal names the two right places", () => {
	assert.throws(
		() =>
			normalizeObservation({
				practiceSlug: "p",
				summary: "Empty change",
				assessmentStatus: "NOT_APPLICABLE",
				presence: null,
				assessment: null,
				severity: null,
				evidenceRationale: "r",
				evidence: {
					citations: [
						{
							sourceKind: "scm.pull-request.diff",
							artifactPath: "inputs/context/change.json",
							path: "change.json",
							side: "NEW",
							startLine: 2,
							quote: '"base_sha" : "a"',
						},
					],
					inapplicability: { consulted: ["scm.pull-request.diff"], subject: "s", ruledOutBy: "r" },
				},
			}),
		/not quotable.*work\/change\/diff\.patch.*metadata\.json/,
	);
});

void test("the cells a practice's Judge section rules out are read from its criteria", () => {
	const criteria = [
		"BEHAVIOR FOCUS: an added line ships an insecure default.",
		"## Judge",
		"Walk every sink class first; then test the cells in this order.",
		"- PRESENT/GOOD and ABSENT/GOOD: no ordinary case. An insecure default is never desirable.",
		"- PRESENT/BAD (NEGATIVE): a concrete added setting whose exposure is inappropriate.",
		"- ABSENT/BAD (POSITIVE): you walked every sink class and none was touched insecurely.",
		"## Severity",
		"- PRESENT/BAD: no ordinary case here would be a different section and is not read.",
	].join("\n");
	assert.deepEqual([...cellsRuledOut(criteria)], ["PRESENT/GOOD", "ABSENT/GOOD"]);
	assert.deepEqual([...cellsRuledOut("## Judge\n- ABSENT/BAD: no ordinary case.")], ["ABSENT/BAD"]);
	assert.equal(cellsRuledOut("# A practice\nCriteria without a Judge section.").size, 0);
});

void test("an observation in a ruled-out cell is refused with the cells the practice names", () => {
	const ruledOut = new Set(["PRESENT/GOOD", "ABSENT/GOOD"]);
	const absent = (assessment: string) =>
		baseObservation({
			presence: "ABSENT",
			assessment,
			severity: assessment === "GOOD" ? "MINOR" : null,
			evidence: {
				citations: baseObservation().evidence.citations,
				search: {
					consulted: ["scm.pull-request.diff"],
					lookedFor: "an insecure default",
					boundary: "the diff",
				},
			},
		});
	// The clean bill a session meant as positive, written as GOOD: recorded, it would be a lapse. It
	// is refused before a severity is asked for, so the session corrects the cell, not the decoration.
	assert.throws(
		() =>
			normalizeObservation(
				baseObservation({ presence: "ABSENT", assessment: "GOOD", severity: null }),
				ruledOut,
			),
		/ABSENT\/GOOD is no ordinary case for 'writes-focused-pull-requests' — its Judge section names PRESENT\/BAD and ABSENT\/BAD\. assessment says whether the behaviour in focus is desirable/,
	);
	assert.equal(normalizeObservation(absent("BAD"), ruledOut).assessment, "BAD");
	// Nothing to judge: an abstention lands in no cell, and an unguarded practice refuses nothing.
	normalizeObservation(
		baseObservation({
			assessmentStatus: "NOT_APPLICABLE",
			presence: null,
			assessment: null,
			severity: null,
			evidence: {
				citations: baseObservation().evidence.citations,
				inapplicability: {
					consulted: ["scm.pull-request.diff"],
					subject: "settings",
					ruledOutBy: "no code",
				},
			},
		}),
		ruledOut,
	);
	assert.equal(normalizeObservation(absent("GOOD")).assessment, "GOOD");
});

void test("a presence written as the status is read as an assessed observation", () => {
	assert.equal(
		normalizeObservation(baseObservation({ assessmentStatus: "PRESENT", presence: undefined }))
			.presence,
		"PRESENT",
	);
	assert.equal(
		normalizeObservation(baseObservation({ assessmentStatus: "present", presence: "PRESENT" }))
			.assessmentStatus,
		"ASSESSED",
	);
	assert.throws(
		() =>
			normalizeObservation(baseObservation({ assessmentStatus: "PRESENT", presence: "ABSENT" })),
		/invalid assessmentStatus 'PRESENT'/,
	);
});
