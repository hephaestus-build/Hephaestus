import assert from "node:assert/strict";
import test from "node:test";

import {
	ASSESSMENT_DESCRIPTIONS,
	ASSESSMENT_VALUES,
	carriesValence,
	citationMatchesArtifact,
	dedupeKeyForObservation,
	describeVocabulary,
	type NormalizedCitation,
	normalizeObservation as normalizeFinalObservation,
	PRESENCE_DESCRIPTIONS,
	PRESENCE_VALUES,
	type Presence,
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
	presence?: string;
	assessment?: string;
	severity?: string;
	reasoning?: unknown;
	evidence?: EvidenceOverrides;
	[key: string]: unknown;
}

type EvidenceFixture = { citations: Record<string, unknown>[] } & Record<string, unknown>;

type EvidenceOverrides = Partial<EvidenceFixture> & Record<string, unknown>;

interface RawObservationFixture {
	practiceSlug: unknown;
	summary: unknown;
	outcome: unknown;
	evidence: EvidenceFixture;
	evidenceRationale: unknown;
	[key: string]: unknown;
}

function baseObservation(overrides: ObservationOverrides = {}): RawObservationFixture {
	const presence = (overrides.presence ?? "PRESENT").toUpperCase();
	const assessment = (overrides.assessment ?? "BAD").toUpperCase();
	const severity = (overrides.severity ?? "MAJOR").toUpperCase();
	const rawEvidence: EvidenceOverrides = overrides.evidence ?? {};
	const citations: Record<string, unknown>[] = Array.isArray(rawEvidence.citations)
		? rawEvidence.citations
		: [
				{
					sourceKind: "scm.pull-request.diff",
					artifactPath: "inputs/context/diff.patch",
					path: "src/Auth.java",
					side: "NEW",
					startLine: 10,
					endLine: 10,
					quote: "+ insecure();",
				},
			];
	const evidence: EvidenceFixture = {
		citations,
		...((rawEvidence.search ?? rawEvidence.exhaustiveSearch) == null
			? {}
			: { exhaustiveSearch: rawEvidence.search ?? rawEvidence.exhaustiveSearch }),
		...((rawEvidence.inapplicability ?? rawEvidence.exclusion) == null
			? {}
			: { exclusion: rawEvidence.inapplicability ?? rawEvidence.exclusion }),
		...((rawEvidence.undecidability ?? rawEvidence.missingEvidence) == null
			? {}
			: { missingEvidence: rawEvidence.undecidability ?? rawEvidence.missingEvidence }),
	};
	const outcome = ["PRESENT", "ABSENT"].includes(presence)
		? `BEHAVIOR_${presence}_${assessment}${assessment === "BAD" ? `_${severity}` : ""}`
		: presence === "NOT_APPLICABLE"
			? "NO_REVIEW_OCCASION"
			: "INSUFFICIENT_EVIDENCE";
	const translated: RawObservationFixture = {
		practiceSlug: overrides.practiceSlug ?? "writes_focused_pull_requests",
		summary: overrides.title ?? "PR mixes unrelated changes",
		outcome,
		evidence,
		evidenceRationale: overrides.reasoning ?? "The diff touches auth and billing in one PR.",
	};
	for (const [key, value] of Object.entries(overrides)) {
		if (
			![
				"title",
				"presence",
				"assessment",
				"severity",
				"reasoning",
				"evidence",
				"summary",
				"outcome",
				"evidenceRationale",
			].includes(key)
		)
			translated[key] = value;
	}
	return translated;
}

function normalizeObservation(input: ObservationOverrides) {
	if (
		"presence" in input ||
		"assessment" in input ||
		"severity" in input ||
		"title" in input ||
		"reasoning" in input
	) {
		return normalizeFinalObservation(baseObservation(input));
	}
	if (
		input.evidence &&
		(input.evidence.search || input.evidence.inapplicability || input.evidence.undecidability)
	) {
		return normalizeFinalObservation({
			...input,
			evidence: {
				citations: input.evidence.citations,
				...(input.evidence.search == null ? {} : { exhaustiveSearch: input.evidence.search }),
				...(input.evidence.inapplicability == null
					? {}
					: { exclusion: input.evidence.inapplicability }),
				...(input.evidence.undecidability == null
					? {}
					: { missingEvidence: input.evidence.undecidability }),
			},
		});
	}
	return normalizeFinalObservation(input);
}

function onlyCitation<T extends object>(citations: readonly T[]): T {
	const [citation] = citations;
	if (!citation) throw new Error("expected the observation to carry exactly one citation");
	return citation;
}

const UNDECIDABLE = {
	openQuestion: "Whether the body states a why, or only restates the title",
	wouldSettleIt: "The body of the issue the description defers to",
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
		baseObservation({ presence: "Present", assessment: "Good", severity: "Minor" }),
	);
	assert.equal(out.presence, "PRESENT");
	assert.equal(out.assessment, "GOOD");
	assert.equal(out.severity, "INFO");
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

void test("NOT_APPLICABLE (lowercase) nulls assessment", () => {
	const out = normalizeObservation(
		notApplicableObservation(goodInapplicability, {
			presence: "not_applicable",
			assessment: "bad",
		}),
	);
	assert.equal(out.presence, "NOT_APPLICABLE");
	assert.equal(out.assessment, undefined);
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
	assert.throws(() => normalizeObservation(baseObservation({ title: "Test" })), /short phrase/);
	assert.throws(
		() => normalizeObservation(baseObservation({ title: "  Duplication  " })),
		/short phrase/,
	);
	assert.equal(normalizeObservation(baseObservation({ title: "No tests" })).summary, "No tests");
});

void test("genuinely invalid enum still rejected after normalization", () => {
	const invalid = baseObservation();
	invalid.outcome = "MAYBE";
	assert.throws(() => normalizeObservation(invalid), /invalid outcome/);
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

	const missingQuote = baseObservation();
	delete onlyCitation(missingQuote.evidence.citations).quote;
	assert.throws(() => normalizeObservation(missingQuote), /quote is required/);
});

void test("citation side is present exactly for pull-request diffs", () => {
	const missingDiffSide = baseObservation();
	delete onlyCitation(missingDiffSide.evidence.citations).side;
	assert.throws(() => normalizeObservation(missingDiffSide), /side must be OLD or NEW/);

	const nonDiffSide = baseObservation();
	onlyCitation(nonDiffSide.evidence.citations).sourceKind = "scm.pull-request.core";
	assert.throws(() => normalizeObservation(nonDiffSide), /must not specify side/);
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
		() => validateEvidenceSources(observation, new Set(["scm.pull-request.diff"]), new Map()),
		/was not staged.*inputs\/manifest\.json/,
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

void test("INCONCLUSIVE is accepted and carries no assessment", () => {
	const out = normalizeObservation({
		...baseObservation(),
		presence: "INCONCLUSIVE",
		assessment: undefined,
		evidence: { ...baseObservation().evidence, undecidability: UNDECIDABLE },
	});
	assert.equal(out.presence, "INCONCLUSIVE");
	assert.equal("assessment" in out, false);
});

void test("an assessment attached to a valence-free presence is dropped, not rejected", () => {
	const observation = (presence: Presence) =>
		presence === "NOT_APPLICABLE"
			? notApplicableObservation(goodInapplicability, { assessment: "GOOD" })
			: {
					...baseObservation(),
					presence,
					assessment: "GOOD",
					evidence: { ...baseObservation().evidence, undecidability: UNDECIDABLE },
				};
	for (const presence of ["NOT_APPLICABLE", "INCONCLUSIVE"] as const) {
		const out = normalizeObservation(observation(presence));
		assert.equal("assessment" in out, false, `${presence} must not carry an assessment`);
	}
});

void test("carriesValence agrees with the presence/assessment coupling", () => {
	assert.equal(carriesValence("PRESENT"), true);
	assert.equal(carriesValence("ABSENT"), true);
	assert.equal(carriesValence("NOT_APPLICABLE"), false);
	assert.equal(carriesValence("INCONCLUSIVE"), false);
	const missingAssessment = baseObservation();
	missingAssessment.outcome = "BEHAVIOR_PRESENT_BAD";
	assert.throws(() => normalizeObservation(missingAssessment), /requires a severity suffix/);
});

function absentObservation(
	search: Partial<RecordedSearch> | undefined,
	overrides: ObservationOverrides = {},
) {
	return {
		...baseObservation(),
		presence: "ABSENT",
		assessment: "BAD",
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
	assert.throws(
		() => normalizeObservation(absentObservation(undefined)),
		/exactly exhaustiveSearch/,
	);
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

void test("a non-ABSENT observation rejects an exhaustive-search branch", () => {
	assert.doesNotThrow(() => normalizeObservation(baseObservation()));
	assert.equal("search" in normalizeObservation(baseObservation()).evidence, false);
	assert.throws(
		() =>
			normalizeObservation({
				...baseObservation(),
				evidence: { ...baseObservation().evidence, search: goodSearch },
			}),
		/exactly citations/,
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
		/without searching scm.linked-work-items/,
	);
	assert.throws(
		() => validateSearchScope(observation, new Set(), new Set(["scm.pull-request.diff"])),
		/was not available.*scm\.pull-request\.diff/,
	);
});

void test("ABSENT + GOOD needs a practice that bounded its corpus; ABSENT + BAD does not", () => {
	const strength = normalizeObservation(
		absentObservation(goodSearch, { assessment: "GOOD", severity: undefined }),
	);
	const gap = normalizeObservation(absentObservation(goodSearch));
	const available = new Set(["scm.review-threads"]);

	assert.doesNotThrow(() =>
		validateSearchScope(strength, new Set(["scm.review-threads"]), available),
	);
	assert.throws(() => validateSearchScope(strength, new Set(), available), /ABSENT \+ GOOD/);
	assert.throws(() => validateSearchScope(strength, new Set(), available), /INCONCLUSIVE/);
	assert.doesNotThrow(() => validateSearchScope(gap, new Set(), available));
});

void test("a bounded corpus does not excuse a partial search, in either direction", () => {
	const strength = normalizeObservation(
		absentObservation(goodSearch, { assessment: "GOOD", severity: undefined }),
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
		presence: "NOT_APPLICABLE",
		assessment: undefined,
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
		/exactly exclusion/,
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

void test("the refusal points at INCONCLUSIVE, because that is the answer it is asking for", () => {
	assert.throws(() => normalizeObservation(notApplicableObservation(undefined)), /exclusion/);
	assert.throws(
		() =>
			normalizeObservation(notApplicableObservation({ ...goodInapplicability, ruledOutBy: "" })),
		/ruledOutBy/,
	);
});

void test("INCONCLUSIVE needs no inapplicability block — it is not claiming anything about the work", () => {
	const out = normalizeObservation({
		...baseObservation(),
		presence: "INCONCLUSIVE",
		assessment: undefined,
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

void test("final discriminated outcomes map to the persisted vocabulary", () => {
	const assessed = baseObservation();
	assert.deepEqual(
		{
			presence: normalizeFinalObservation(assessed).presence,
			assessment: normalizeFinalObservation(assessed).assessment,
		},
		{ presence: "PRESENT", assessment: "BAD" },
	);

	const declined = baseObservation({
		presence: "INCONCLUSIVE",
		evidence: { undecidability: UNDECIDABLE },
	});
	const normalized = normalizeFinalObservation(declined);
	assert.equal(normalized.presence, "INCONCLUSIVE");
	assert.equal(normalized.assessment, undefined);
});

void test("outcome is one exact semantic value rather than nullable peer fields", () => {
	const objectOutcome = baseObservation();
	objectOutcome.outcome = { kind: "ASSESSED", occurrence: "PRESENT", assessment: "GOOD" };
	assert.throws(() => normalizeFinalObservation(objectOutcome), /invalid outcome/);

	const invalidGood = baseObservation();
	invalidGood.outcome = "BEHAVIOR_PRESENT_GOOD_MINOR";
	assert.throws(() => normalizeFinalObservation(invalidGood), /must not carry severity/);
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

void test("each presence description discriminates it from its nearest neighbour", () => {
	const rendered = describeVocabulary(PRESENCE_VALUES, PRESENCE_DESCRIPTIONS);
	for (const value of PRESENCE_VALUES) {
		assert.ok(rendered.includes(`${value} — `), `${value} is rendered on its own line`);
		const others = PRESENCE_VALUES.filter((other) => other !== value);
		assert.ok(
			others.some((other) => PRESENCE_DESCRIPTIONS[value].includes(other)),
			`${value} must say how it differs from another presence`,
		);
	}
});

void test("a citation survives the typographic substitutions a model makes while transcribing", () => {
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
		true,
	);
	assert.equal(citationMatchesArtifact(cite("see the plan"), content), true);
});

void test("folding glyphs never makes a quote the artifact does not contain match", () => {
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

void test("an INCONCLUSIVE observation must say what it could not settle", () => {
	const base = {
		practiceSlug: "describe-what-and-why",
		title: "Rationale lives somewhere this review cannot read",
		presence: "INCONCLUSIVE",
		reasoning: "The body points at an issue for the why, and that issue was not staged.",
		evidence: { citations: baseObservation().evidence.citations },
	};

	assert.throws(() => normalizeObservation(base), /missingEvidence/);
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
	assert.equal(ok.presence, "INCONCLUSIVE");
	assert.equal(ok.assessment, undefined);
	assert.equal(ok.evidence.undecidability?.wouldSettleIt, "The linked issue's body");
});
