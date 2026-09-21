import assert from "node:assert/strict";
import test from "node:test";

import {
	ASSESSMENT_DESCRIPTIONS,
	ASSESSMENT_VALUES,
	citationMatchesArtifact,
	describeCitationMismatch,
	dedupeKeyForObservation,
	describeVocabulary,
	type NormalizedCitation,
	normalizeObservation as normalizeFinalObservation,
	PRESENCE_DESCRIPTIONS,
	PRESENCE_VALUES,
	type RecordedInapplicability,
	type RecordedSearch,
	SEVERITY_DESCRIPTIONS,
	SEVERITY_VALUES,
	validateEvidenceSources,
	validateInapplicabilityScope,
	validateSearchScope,
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
	if (!citation) {
		throw new Error("expected the observation to carry exactly one citation");
	}
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
			/unknown observation field.*confidence/u,
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
	assert.throws(() => normalizeObservation(baseObservation({ summary: "Test" })), /short phrase/u);
	assert.throws(
		() => normalizeObservation(baseObservation({ summary: "  Duplication  " })),
		/short phrase/u,
	);
	assert.equal(normalizeObservation(baseObservation({ summary: "No tests" })).summary, "No tests");
});

void test("genuinely invalid enum still rejected after normalization", () => {
	const invalid = baseObservation();
	invalid.presence = "MAYBE";
	assert.throws(() => normalizeObservation(invalid), /invalid presence/u);
});

void test("missing evidence-source attribution is rejected", () => {
	assert.throws(
		() => normalizeObservation(baseObservation({ evidence: { citations: [] } })),
		/citations are required/u,
	);
});

void test("citation requires an exact artifact path and quote", () => {
	const missingPath = baseObservation();
	delete onlyCitation(missingPath.evidence.citations).artifactPath;
	assert.throws(() => normalizeObservation(missingPath), /artifactPath is required/u);

	const missingQuote = baseObservation();
	delete onlyCitation(missingQuote.evidence.citations).quote;
	assert.throws(() => normalizeObservation(missingQuote), /quote is required/u);
});

void test("citation side is present exactly for pull-request diffs", () => {
	const missingDiffSide = baseObservation();
	delete onlyCitation(missingDiffSide.evidence.citations).side;
	assert.throws(() => normalizeObservation(missingDiffSide), /side must be OLD or NEW/u);

	const nonDiffSide = baseObservation();
	onlyCitation(nonDiffSide.evidence.citations).sourceKind = "scm.pull-request.core";
	assert.throws(() => normalizeObservation(nonDiffSide), /must not specify side/u);
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
		/was not available.*scm\.pull-request\.core, scm\.review-threads/u,
	);
	assert.throws(
		() =>
			validateEvidenceSources(
				observation,
				new Set(["scm.pull-request.diff"]),
				new Map([["inputs/context/diff.patch", "scm.pull-request.core"]]),
			),
		/belongs to evidence source 'scm\.pull-request\.core', not 'scm\.pull-request\.diff'/u,
	);
	assert.throws(
		() => validateEvidenceSources(observation, new Set(["scm.pull-request.diff"]), new Map()),
		/was not staged.*task-declared manifest/u,
	);
});

void test("diff citations bind the quote to the claimed file and line", () => {
	const citation = onlyCitation(normalizeObservation(baseObservation()).evidence.citations);
	const diff =
		"diff --git a/src/Auth.java b/src/Auth.java\n+++ b/src/Auth.java\n@@ -10 +10 @@\n[L10] + insecure();\n";
	assert.equal(citationMatchesArtifact(citation, diff), true);
	assert.equal(citationMatchesArtifact({ ...citation, path: "src/Other.java" }, diff), false);
	assert.equal(citationMatchesArtifact({ ...citation, startLine: 11 }, diff), false);
	assert.equal(citationMatchesArtifact({ ...citation, endLine: 12 }, diff), false);
});

void test("a diff quote may omit its marker but must preserve source indentation", () => {
	const citation = onlyCitation(normalizeObservation(baseObservation()).evidence.citations);
	const diff =
		"diff --git a/src/Auth.java b/src/Auth.java\n+++ b/src/Auth.java\n@@ -10 +10 @@\n[L10] +    insecure();\n";

	// Preserve the source indentation whether or not the diff marker is included.
	assert.notEqual(describeCitationMismatch({ ...citation, quote: "insecure();" }, diff), null);
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

	assert.match(describeCitationMismatch(citation, diff) ?? "", /\[L47\] reads/u);
});

void test("a quote cannot carry display coordinates absent from the source line", () => {
	const citation = onlyCitation(normalizeObservation(baseObservation()).evidence.citations);
	const diff =
		"diff --git a/src/Auth.java b/src/Auth.java\n+++ b/src/Auth.java\n@@ -10 +10 @@\n[L10] + insecure();\n";

	// Display coordinates locate the source; they are not part of its quoted text.
	assert.notEqual(
		describeCitationMismatch({ ...citation, quote: "[L10] + insecure();" }, diff),
		null,
	);
	// The coordinate still has to be the one being matched, so a quote cannot claim a line it did
	// not read — even when that line's text is in the diff somewhere else.
	assert.match(
		describeCitationMismatch({ ...citation, quote: "[L11] + insecure();" }, diff) ?? "",
		/\[L10\] reads/u,
	);
});

void test("a refused citation says which of the coordinate, the side and the text was wrong", () => {
	const citation = onlyCitation(normalizeObservation(baseObservation()).evidence.citations);
	const diff =
		"diff --git a/src/Auth.java b/src/Auth.java\n+++ b/src/Auth.java\n@@ -10 +10 @@\n[L10] + insecure();\n";

	assert.equal(describeCitationMismatch(citation, diff), null);
	// The coordinate is not in the diff at all.
	assert.match(
		describeCitationMismatch({ ...citation, startLine: 11, endLine: 11 }, diff) ?? "",
		/no \[L11\] on the NEW side of src\/Auth\.java/u,
	);
	// The coordinate is there and says something else, so the refusal shows both.
	assert.match(
		describeCitationMismatch({ ...citation, quote: "+ secure();" }, diff) ?? "",
		/\[L10\] reads "\+ insecure\(\);", not "\+ secure\(\);"/u,
	);
	// The quote and the line span disagree.
	assert.match(
		describeCitationMismatch({ ...citation, endLine: 12 }, diff) ?? "",
		/quote is 1 line\(s\) and the citation covers 3/u,
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
			/explicit null/u,
		);
	}
	assert.throws(
		() => normalizeObservation(baseObservation({ assessment: "GOOD" })),
		/null severity/u,
	);
	assert.throws(
		() => normalizeObservation(baseObservation({ severity: null })),
		/invalid severity/u,
	);
	assert.throws(
		() => normalizeObservation(baseObservation({ presence: null })),
		/invalid presence/u,
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
	assert.throws(() => normalizeObservation(absentObservation(undefined)), /exactly search/u);
	assert.throws(
		() => normalizeObservation(absentObservation({ ...goodSearch, consulted: [] })),
		/at least one source/u,
	);
	assert.throws(
		() => normalizeObservation(absentObservation({ ...goodSearch, lookedFor: " " })),
		/lookedFor is required/u,
	);
	assert.throws(
		() => normalizeObservation(absentObservation({ ...goodSearch, boundary: "" })),
		/boundary is required/u,
	);

	const out = normalizeObservation(absentObservation(goodSearch));
	assert.deepEqual(out.evidence.search?.consulted, ["scm.review-threads"]);
});

void test("a non-ABSENT observation rejects an exhaustive-search branch", () => {
	assert.doesNotThrow(() => normalizeObservation(baseObservation()));
	assert.equal("search" in normalizeObservation(baseObservation()).evidence, false);
	assert.throws(
		() =>
			normalizeObservation({
				...baseObservation(),
				evidence: { ...baseObservation().evidence, search: goodSearch },
			}),
		/exactly citations/u,
	);
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
		/without searching scm.linked-work-items/u,
	);
	assert.throws(
		() => validateSearchScope(observation, new Set(), new Set(["scm.pull-request.diff"])),
		/was not available.*scm\.pull-request\.diff/u,
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
	assert.throws(() => validateSearchScope(strength, new Set(), available), /ABSENT \+ BAD/u);
	assert.throws(() => validateSearchScope(strength, new Set(), available), /UNDETERMINED/u);
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
		/without searching scm.linked-work-items/u,
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
	assert.equal(
		citationMatchesArtifact(
			{ ...onlyCitation(observation.evidence.citations), quote: '"recurrenceKey": "invented"' },
			bytes,
		),
		false,
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
		/exactly inapplicability/u,
	);
	assert.throws(
		() => normalizeObservation(notApplicableObservation({ ...goodInapplicability, consulted: [] })),
		/at least one source/u,
	);
	assert.throws(
		() => normalizeObservation(notApplicableObservation({ ...goodInapplicability, subject: " " })),
		/subject is required/u,
	);
	assert.throws(
		() =>
			normalizeObservation(notApplicableObservation({ ...goodInapplicability, ruledOutBy: "" })),
		/ruledOutBy is required/u,
	);

	const out = normalizeObservation(notApplicableObservation(goodInapplicability));
	const { inapplicability } = out.evidence;
	assert.ok(inapplicability, "a NOT_APPLICABLE observation must come back carrying its ground");
	assert.deepEqual(inapplicability.consulted, ["scm.pull-request.diff"]);
	assert.equal(inapplicability.subject, goodInapplicability.subject);
});

void test("the refusal points at UNDETERMINED, because that is the answer it is asking for", () => {
	assert.throws(
		() => normalizeObservation(notApplicableObservation(undefined)),
		/inapplicability/u,
	);
	assert.throws(
		() =>
			normalizeObservation(notApplicableObservation({ ...goodInapplicability, ruledOutBy: "" })),
		/ruledOutBy/u,
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
		/was not available.*scm\.review-threads/u,
	);

	const present = normalizeObservation(baseObservation());
	assert.doesNotThrow(() => validateInapplicabilityScope(present, new Set()));
});

void test("removed measurement fields are rejected rather than silently accepted", () => {
	assert.throws(
		() => normalizeObservation(baseObservation({ guidance: "Split into two PRs." })),
		/unknown observation field.*guidance/u,
	);
	assert.throws(
		() => normalizeObservation(baseObservation({ suggestedDiffNotes: [] })),
		/unknown observation field.*suggestedDiffNotes/u,
	);
});

void test("all assessed combinations preserve the specified behavior and judgment", () => {
	for (const presence of PRESENCE_VALUES) {
		for (const assessment of ASSESSMENT_VALUES) {
			const severity = (presence === "PRESENT") === (assessment === "GOOD") ? null : "MAJOR";
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
		/unknown observation field/u,
	);
	assert.throws(
		() => normalizeFinalObservation({ ...baseObservation(), assessmentStatus: undefined }),
		/invalid assessmentStatus/u,
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
		/'UNDECIDED' has no description/u,
	);
});

/** A citation of the pull request title, whose only variable part is the quoted text. */
function titleCitation(quote: string): NormalizedCitation {
	return {
		sourceKind: "scm.pull-request.core",
		artifactPath: "inputs/context/core.md",
		path: "title",
		startLine: 1,
		endLine: 1,
		quote,
	};
}

void test("non-diff citations preserve exact punctuation", () => {
	const content = 'Resolve "Connect data between screens" — see the plan';

	assert.equal(
		citationMatchesArtifact(titleCitation('Resolve "Connect data between screens"'), content),
		true,
	);
	assert.equal(
		citationMatchesArtifact(titleCitation("Resolve “Connect data between screens”"), content),
		false,
	);
	assert.equal(citationMatchesArtifact(titleCitation("see the plan"), content), true);
});

void test("non-diff citations reject text absent from the artifact", () => {
	const content = 'Resolve "Connect data between screens"';

	assert.equal(
		citationMatchesArtifact(titleCitation("Resolve “Disconnect data between screens”"), content),
		false,
	);
	assert.equal(
		citationMatchesArtifact(titleCitation("a rationale the author never wrote"), content),
		false,
	);
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

	assert.throws(() => normalizeObservation(base), /undecidability/u);
	assert.throws(
		() =>
			normalizeObservation({
				...base,
				evidence: { ...base.evidence, undecidability: { openQuestion: "x" } },
			}),
		/wouldSettleIt/u,
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
	onlyCitation(raw.evidence.citations).quote = " \t\n";
	assert.throws(() => normalizeObservation(raw), /quote is required/u);
});

void test("citation coordinates are bounded and control-only quotes are blank", () => {
	for (const line of [2_147_483_648, 4_294_967_306, Number.MAX_SAFE_INTEGER + 1]) {
		const raw = baseObservation();
		onlyCitation(raw.evidence.citations).startLine = line;
		assert.throws(() => normalizeObservation(raw), /startLine/u);
		onlyCitation(raw.evidence.citations).startLine = 10;
		onlyCitation(raw.evidence.citations).endLine = line;
		assert.throws(() => normalizeObservation(raw), /endLine/u);
	}
	const raw = baseObservation();
	onlyCitation(raw.evidence.citations).quote = "\u001C\u001D\u001E\u001F";
	assert.throws(() => normalizeObservation(raw), /quote is required/u);
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
		assert.throws(() => normalizeObservation(blank), new RegExp(`${field} is required`, "u"));
	}
});
