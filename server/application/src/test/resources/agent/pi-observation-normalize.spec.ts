import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import {
	citationMatchesArtifact,
	describeCitationMismatch,
	MAX_SUMMARY_CHARS,
	type NormalizedCitation,
	normalizeObservation as normalizeFinalObservation,
	normalizeEvidence,
	validateEvidenceSources,
	validateInapplicabilityScope,
	validateSearchScope,
	resolveQuote,
	withoutCoordinates,
} from "../../../main/resources/agent/pi-observation-normalize.ts";

interface ObservationOverrides {
	practiceSlug?: unknown;
	title?: unknown;
	outcome?: unknown;
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
		outcome: "NOT_MET",
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
	assert.throws(() => normalizeObservation(cited({})), /startLine is required: the 1-based line/u);
	assert.throws(() => normalizeObservation(cited({ startLine: null })), /startLine is required/u);
	assert.throws(
		() => normalizeObservation(cited({ startLine: 0 })),
		/startLine must be a positive integer, received 0; lines are 1-based/u,
	);
	assert.throws(
		() => normalizeObservation(cited({ startLine: "ten" })),
		/received "ten"; lines are 1-based/u,
	);
	assert.throws(
		() => normalizeObservation(cited({ startLine: 10, endLine: 4 })),
		/endLine must be an integer >= startLine, received 4 with startLine 10/u,
	);
});

void test("an item with no practiceSlug is refused as not an observation, before its cell is read", () => {
	assert.throws(
		() => normalizeObservation({ summary: "PR mixes unrelated changes" }),
		/practiceSlug is required: each item of observations is one observation object \(received keys: summary\)/u,
	);
	assert.throws(() => normalizeObservation({}), /received keys: none/u);
});

void test("an observation carries no confidence, and one offered is rejected", () => {
	const out = normalizeObservation(baseObservation());
	assert.equal("confidence" in out, false);
	for (const confidence of [-1, 4200, "very"]) {
		assert.throws(
			() => normalizeObservation(baseObservation({ confidence })),
			/unknown observation field\(s\): confidence; an observation has only practiceSlug, summary, outcome, severity, evidence, evidenceRationale$/u,
		);
	}
	// A stray key with no value carries nothing: dropped, and said so.
	for (const confidence of [null, ""]) {
		const notes: string[] = [];
		assert.equal(
			"confidence" in normalizeObservation(baseObservation({ confidence }), notes),
			false,
		);
		assert.deepEqual(notes, ["empty field(s) confidence dropped"]);
	}
});

void test("practice slugs normalize to one canonical identity", () => {
	const a = normalizeObservation(baseObservation({ practiceSlug: "writes_focused_pull_requests" }));
	const b = normalizeObservation(baseObservation({ practiceSlug: "WRITES-FOCUSED-PULL-REQUESTS" }));
	assert.equal(a.practiceSlug, b.practiceSlug);
});

void test("deduplication does not discard a contradictory outcome", () => {
	const met = normalizeObservation(baseObservation({ outcome: "MET", severity: null }));
	const notMet = normalizeObservation(baseObservation({ outcome: "NOT_MET", severity: "MAJOR" }));
	assert.notDeepEqual(met, notMet);
});

void test("a correction to severity or rationale is not an exact retry", () => {
	const initial = normalizeObservation(baseObservation());
	const changedSeverity = normalizeObservation(baseObservation({ severity: "MINOR" }));
	const changedRationale = normalizeObservation(
		baseObservation({
			evidenceRationale: "The complete captured change establishes a different consequence.",
		}),
	);
	assert.notDeepEqual(initial, changedSeverity);
	assert.notDeepEqual(initial, changedRationale);
});

void test("a one-word summary is refused, because it names nothing on the practice page", () => {
	assert.throws(() => normalizeObservation(baseObservation({ summary: "Test" })), /short phrase/u);
	assert.throws(
		() => normalizeObservation(baseObservation({ summary: "  Duplication  " })),
		/short phrase/u,
	);
	assert.equal(normalizeObservation(baseObservation({ summary: "No tests" })).summary, "No tests");
});

void test("a summary over the bound is refused whole, never recorded as a fragment of itself", () => {
	const quotedTitle =
		"The MR names the issue it implements via 'Closes #1' in the body and '#1' in the title, " +
		"resolved by the platform to issue #1 'Day 1: Make your first merge request'";
	assert.equal(quotedTitle.length, MAX_SUMMARY_CHARS + 3);
	assert.throws(
		() => normalizeObservation(baseObservation({ summary: quotedTitle })),
		new RegExp(
			`summary must be at most ${MAX_SUMMARY_CHARS} characters; this one is ${quotedTitle.length}\\. ` +
				"Resend the observation with a shorter summary that reads as a complete phrase on its own",
			"u",
		),
	);
	const atTheLimit = `${"x ".repeat(MAX_SUMMARY_CHARS / 2).trim()}x`;
	assert.equal(atTheLimit.length, MAX_SUMMARY_CHARS);
	assert.equal(normalizeObservation(baseObservation({ summary: atTheLimit })).summary, atTheLimit);
	// Runs of whitespace are one space: a summary is one line on the page.
	assert.equal(
		normalizeObservation(baseObservation({ summary: "PR mixes\n  unrelated   changes" })).summary,
		"PR mixes unrelated changes",
	);
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
	assert.throws(() => normalizeObservation(wrongSide), /side must be OLD or NEW/u);

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
		() =>
			validateEvidenceSources(
				observation,
				new Set(["scm.pull-request.diff"]),
				new Map([["inputs/context/change.json", "scm.pull-request.diff"]]),
			),
		/was not staged; the staged artifacts are: inputs\/context\/change\.json\.$/u,
	);
	// The change view is derived in the container; a citation of it is told what the artifact is.
	const derived = normalizeObservation(baseObservation());
	onlyCitation(derived.evidence.citations).artifactPath = "work/change/files.json";
	assert.throws(
		() => validateEvidenceSources(derived, new Set(["scm.pull-request.diff"]), new Map()),
		/work\/ is derived here and is not an artifact: quote a changed line from work\/change\/diff\.patch/u,
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
	const citation = onlyCitation(normalizeObservation(baseObservation()).evidence.citations);
	const diff =
		"diff --git a/run.sh b/run.sh\n+++ b/run.sh\n@@ -10,2 +10,2 @@\n[L10] +    -flag --now\n[L11] +  next\n";
	const cited = { ...citation, path: "run.sh", endLine: 11 };
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
		/cited lines are blank/u,
	);
	assert.match(mismatch(resolveQuote({ ...cited, quote: "", endLine: 12 }, diff)), /no \[L12\]/u);
	// What does not match is refused with what the line reads.
	assert.match(
		mismatch(resolveQuote({ ...cited, quote: "+    -flag\n+  next" }, diff)),
		/\[L10\] reads/u,
	);
});

void test("a quote is recorded as the artifact spells it: JSON escapes and non-breaking spaces", () => {
	const { side: _side, ...plain } = onlyCitation(
		normalizeObservation(baseObservation()).evidence.citations,
	);
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
		mismatch(resolveQuote({ ...first, quote: "" }, `${"x".repeat(2001)}\n`)),
		/cite fewer lines or quote a fragment/u,
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

	assert.match(describeCitationMismatch(citation, diff) ?? "", /\[L47\] reads/u);
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
		/\[L10\] reads/u,
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
	const citation = onlyCitation(normalizeObservation(baseObservation()).evidence.citations);
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

void test("a coordinate copied from a numbered view of diff.patch is read as the line it names", () => {
	const citation = onlyCitation(normalizeObservation(baseObservation()).evidence.citations);
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
	const citation = onlyCitation(normalizeObservation(baseObservation()).evidence.citations);
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

const goodSearch = {
	consulted: ["scm.review-threads"],
	lookedFor: "a review thread raising the migration",
	boundary: "only threads on this pull request; nothing in chat",
};

void test("a decided claim preserves its submitted bounded search", () => {
	assert.doesNotThrow(() => normalizeObservation(baseObservation()));
	assert.equal("search" in normalizeObservation(baseObservation()).evidence, false);
	const withSurplus = normalizeObservation({
		...baseObservation(),
		evidence: { ...baseObservation().evidence, search: goodSearch },
	});
	assert.deepEqual(withSurplus.evidence.search, goodSearch);
	assert.equal(withSurplus.evidence.citations.length, 1);
});

void test("a direct-evidence claim does not require a search warrant", () => {
	const direct = normalizeObservation(baseObservation());
	assert.doesNotThrow(() =>
		validateSearchScope(direct, new Set(["scm.review-threads"]), new Set()),
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
		/^\[L1\] reads .*; this is a JSON string whose line breaks are the two characters \\n, so quote a fragment from between two of them, or spell them as the line does$/u,
	);
	assert.equal(
		describeCitationMismatch({ ...invented, quote: "- [x] stored in `diagrams/`" }, serialized),
		null,
	);
});

const goodInapplicability = {
	consulted: ["scm.pull-request.diff"],
	subject: "error handling around outbound network calls",
	ruledOutBy: "the change touches only Markdown documentation and makes no network calls",
};

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
	assert.equal(
		normalizeEvidence({ citations: [citation] }, "NOT_MET").citations[0]?.revision,
		citation.revision,
	);
	assert.throws(
		() => normalizeEvidence({ citations: [{ ...citation, revision: "HEAD~1" }] }, "NOT_MET"),
		/full commit SHA/u,
	);
	// Whether a revision applies is decided once the manifest has settled the source, so it is kept
	// here; the runner drops it from any citation that is not of the repository, with a note.
	assert.equal(
		normalizeEvidence({ citations: [{ ...citation, sourceKind: "scm.issue.core" }] }, "NOT_MET")
			.citations[0]?.revision,
		citation.revision,
	);
});

void test("a citation is completed from the manifest and its line numbers read as written", () => {
	const notes: string[] = [];
	const staged = new Map([
		["inputs/context/metadata.json", "scm.pull-request.core"],
		["inputs/context/change.json", "scm.pull-request.diff"],
	]);
	const [record, change] = normalizeEvidence(
		{
			citations: [
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
		},
		"NOT_MET",
		{ sourceOf: (artifact) => staged.get(artifact), notes },
	).citations;
	assert.deepEqual(
		[record?.artifactPath, record?.sourceKind, record?.startLine],
		["inputs/context/metadata.json", "scm.pull-request.core", 3],
	);
	assert.deepEqual(
		[change?.sourceKind, change?.startLine, change?.endLine],
		["scm.pull-request.diff", 10, 12],
	);
	assert.deepEqual(notes, [
		"citation 1: artifactPath filled in as inputs/context/metadata.json, the staged record the path names",
		"citation 1: sourceKind filled in as scm.pull-request.core, the source that staged inputs/context/metadata.json",
		'citation 1: startLine "[L3]" read as 3',
		"citation 2: sourceKind filled in as scm.pull-request.diff, the source that staged inputs/context/change.json",
		'citation 2: startLine "L10" read as 10',
		'citation 2: endLine "12" read as 12',
	]);
	// A single citation's notes need no number; the observation passes the manifest through.
	const single: string[] = [];
	const observation = normalizeObservation(
		baseObservation({
			evidence: {
				citations: [{ path: "inputs/context/metadata.json", startLine: "[L3]", quote: "x" }],
			},
		}),
		single,
		(artifact) => staged.get(artifact),
	);
	assert.equal(onlyCitation(observation.evidence.citations).sourceKind, "scm.pull-request.core");
	assert.deepEqual(single, [
		"artifactPath filled in as inputs/context/metadata.json, the staged record the path names",
		"sourceKind filled in as scm.pull-request.core, the source that staged inputs/context/metadata.json",
		'startLine "[L3]" read as 3',
	]);
	// What the manifest does not know is still asked for, naming the citation it is missing from.
	assert.throws(
		() =>
			normalizeEvidence(
				{
					citations: [
						{ path: "a.ts", startLine: 1 },
						{ path: "b.ts", startLine: 1 },
					],
				},
				"NOT_MET",
				{ sourceOf: () => undefined },
			),
		/^Error: citation 1: evidence citation sourceKind is required$/u,
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
	for (const line of [2_147_483_648, 4_294_967_306, Number.MAX_SAFE_INTEGER + 1]) {
		const raw = baseObservation();
		onlyCitation(raw.evidence.citations).startLine = line;
		assert.throws(() => normalizeObservation(raw), /startLine/u);
		onlyCitation(raw.evidence.citations).startLine = 10;
		onlyCitation(raw.evidence.citations).endLine = line;
		assert.throws(() => normalizeObservation(raw), /endLine/u);
	}
});

void test("annotated source text is not parsed as a header and Unicode separators remain source text", () => {
	const cited = onlyCitation(normalizeObservation(baseObservation()).evidence.citations);
	const diff =
		"--- a/src/Auth.java\n+++ b/src/Auth.java\n[L10] --- SQL comment\n[L10] +++ value\u2028tail\u2029end\n";
	assert.equal(
		describeCitationMismatch({ ...cited, side: "OLD", quote: "-- SQL comment" }, diff),
		null,
	);
	assert.equal(
		describeCitationMismatch({ ...cited, quote: "++ value\u2028tail\u2029end" }, diff),
		null,
	);
	assert.notEqual(
		describeCitationMismatch(
			{ ...cited, endLine: 11, quote: "x\n\n" },
			"--- a/src/Auth.java\n+++ b/src/Auth.java\n[L10] +x\n[L11] ",
		),
		null,
	);
	assert.equal(
		describeCitationMismatch({ ...cited, path: '"', quote: "x" }, '--- "\n+++ "\n[L10] +x\n'),
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

void test("a quote copied with the brief's line coordinates is stored without them", () => {
	assert.equal(withoutCoordinates("[L15] to test the winner", 15), "to test the winner");
	assert.equal(withoutCoordinates("[L15] first\n[L16] second\n", 15), "first\nsecond\n");
	// A coordinate that names another line is text, and stays.
	assert.equal(withoutCoordinates("[L9] elsewhere", 15), "[L9] elsewhere");
	assert.equal(withoutCoordinates("plain", 3), "plain");
});

void test("incompatible evidence warrants are rejected, not discarded", () => {
	assert.throws(
		() =>
			normalizeObservation(baseObservation({ evidence: { inapplicability: goodInapplicability } })),
		/inapplicability is permitted only/u,
	);
	assert.throws(
		() => normalizeObservation(baseObservation({ evidence: { undecidability: UNDECIDABLE } })),
		/undecidability is permitted only/u,
	);
	assert.throws(
		() =>
			normalizeObservation(
				baseObservation({
					outcome: "NOT_APPLICABLE",
					severity: null,
					evidence: { inapplicability: goodInapplicability, search: goodSearch },
				}),
			),
		/search is permitted only/u,
	);
	assert.throws(
		() =>
			normalizeObservation(
				baseObservation({
					outcome: "UNDETERMINED",
					severity: null,
					evidence: { undecidability: UNDECIDABLE, search: goodSearch },
				}),
			),
		/search is permitted only/u,
	);
});

void test("a field sent where it does not belong is read from there and named", () => {
	const { evidence, ...rest } = baseObservation();
	const notes: string[] = [];
	// citations beside the observation, and the rationale under evidence: each moved to its home.
	const { evidenceRationale, ...withoutRationale } = rest;
	const rehomed = normalizeObservation(
		{ ...withoutRationale, citations: evidence.citations, evidence: { evidenceRationale } },
		notes,
	);
	assert.equal(rehomed.evidenceRationale, evidenceRationale);
	assert.equal(rehomed.evidence.citations.length, 1);
	assert.deepEqual(notes, [
		"evidenceRationale read from under evidence and recorded beside it, where it belongs; nothing to resend",
		"citations read from where they were sent and recorded under evidence, where they belong; nothing to resend",
	]);
	// The search fields beside a MET observation, with no search wrapper at all.
	const met = normalizeObservation(
		{ ...rest, outcome: "MET", severity: null, evidence, ...goodSearch },
		notes,
	);
	assert.deepEqual(met.evidence.search, goodSearch);
	assert.equal(
		notes.at(-1),
		"search{lookedFor, boundary, consulted} read from where they were sent and recorded under evidence, where they belong; nothing to resend",
	);
	// The fields of an inapplicability, sent straight under evidence: its own fields name the branch.
	const inapplicable = normalizeObservation(
		{
			...rest,
			outcome: "NOT_APPLICABLE",
			severity: null,
			evidence: { citations: evidence.citations, ...goodInapplicability },
		},
		notes,
	);
	assert.deepEqual(inapplicable.evidence.inapplicability, goodInapplicability);
	assert.equal(
		notes.at(-1),
		"inapplicability{subject, ruledOutBy, consulted} read from where they were sent and recorded under evidence, where they belong; nothing to resend",
	);
	// A rehomed branch is still held to its outcome: an inapplicability beside NOT_MET is refused.
	assert.throws(
		() => normalizeObservation({ ...rest, evidence, ...goodInapplicability }),
		/evidence\.inapplicability is permitted only for NOT_APPLICABLE/u,
	);
	// A field present in both places is not guessed at: the unknown-field check names it.
	assert.throws(
		() => normalizeObservation({ ...rest, evidence, citations: [] }),
		/unknown observation field\(s\): citations/u,
	);
});

void test("loose warrant fields sent in both places are refused without choosing a claim", () => {
	for (const [field, beside, underEvidence] of [
		["lookedFor", "a human approval", "a regression test"],
		["subject", "a review request", "an acceptance check"],
		["openQuestion", "whether approval is current", "whether a test ran"],
	] as const) {
		assert.throws(
			() =>
				normalizeObservation(
					baseObservation({
						[field]: beside,
						evidence: { citations: baseObservation().evidence.citations, [field]: underEvidence },
					}),
				),
			new RegExp(`${field} was sent both beside the observation and under evidence`, "u"),
		);
	}
});

void test("the wire contract rejects outcome aliases and text coerced from numbers", () => {
	for (const outcome of ["met", "NOT-MET", "not met", " MET "]) {
		assert.throws(() => normalizeObservation(baseObservation({ outcome })), /invalid outcome/u);
	}
	assert.throws(
		() => normalizeObservation(baseObservation({ outcome: "MET", severity: "null" })),
		/Severity/u,
	);
	assert.throws(() => normalizeObservation(baseObservation({ summary: 1234 })), /summary/u);
});

void test("one outcome records the standard, with severity exactly for NOT_MET", () => {
	for (const outcome of ["MET", "NOT_MET", "NOT_APPLICABLE", "UNDETERMINED"] as const) {
		const evidence: EvidenceOverrides = {};
		if (outcome === "NOT_APPLICABLE") {
			evidence.inapplicability = goodInapplicability;
		}
		if (outcome === "UNDETERMINED") {
			evidence.undecidability = UNDECIDABLE;
		}
		const observation = normalizeObservation(
			baseObservation({ outcome, severity: outcome === "NOT_MET" ? "MAJOR" : null, evidence }),
		);
		assert.equal(observation.outcome, outcome);
		assert.equal(observation.severity, outcome === "NOT_MET" ? "MAJOR" : null);
	}
	assert.throws(
		() => normalizeObservation(baseObservation({ outcome: "MET" })),
		/Severity is permitted only for NOT_MET/u,
	);
	assert.throws(
		() => normalizeObservation(baseObservation({ severity: null })),
		/invalid severity/u,
	);
});

void test("old axes and unknown outcomes are rejected, never interpreted", () => {
	for (const field of ["presence", "assessment", "assessmentStatus"]) {
		assert.throws(
			() => normalizeObservation(baseObservation({ [field]: "legacy" })),
			/unknown observation field/u,
		);
	}
	assert.throws(
		() => normalizeObservation(baseObservation({ outcome: "POSITIVE" })),
		/invalid outcome/u,
	);
});

void test("abstentions require distinct evidence warrants", () => {
	assert.throws(
		() => normalizeObservation(baseObservation({ outcome: "NOT_APPLICABLE", severity: null })),
		/inapplicability/u,
	);
	assert.throws(
		() => normalizeObservation(baseObservation({ outcome: "UNDETERMINED", severity: null })),
		/undecidability/u,
	);
	const observation = normalizeObservation(
		baseObservation({
			outcome: "NOT_APPLICABLE",
			severity: null,
			evidence: { inapplicability: goodInapplicability },
		}),
	);
	assert.throws(() => validateInapplicabilityScope(observation, new Set()), /was not available/u);
});

void test("an absence-based MET claim needs exhaustive captured evidence", () => {
	const observation = normalizeObservation(
		baseObservation({ outcome: "MET", severity: null, evidence: { search: goodSearch } }),
	);
	assert.throws(
		() => validateSearchScope(observation, new Set(), new Set(goodSearch.consulted)),
		/^Error: MET for 'writes-focused-pull-requests' rests on an absence claim, and the practice declares no source it searches exhaustively, so no search can bound that claim\. Record what the evidence does show: MET from cited evidence, NOT_APPLICABLE when the work gives the practice no occasion, or UNDETERMINED with what would settle it$/u,
	);
	assert.throws(
		() => validateSearchScope(observation, new Set(["unread"]), new Set(goodSearch.consulted)),
		/^Error: an absence claim for 'writes-focused-pull-requests' rests on searching unread as well: add it to evidence\.search\.consulted once you have searched it$/u,
	);
	validateSearchScope(observation, new Set(goodSearch.consulted), new Set(goodSearch.consulted));
});

void test("a NOT_MET search names every exhaustive source it left out", () => {
	const observation = normalizeObservation(baseObservation({ evidence: { search: goodSearch } }));
	const available = new Set(["scm.review-threads", "scm.linked-work-items", "scm.issue.core"]);
	// NOT_MET from a bounded search is not an absence-only MET: no exhaustive source is required.
	assert.doesNotThrow(() => validateSearchScope(observation, new Set(), available));
	assert.throws(
		() =>
			validateSearchScope(
				observation,
				new Set(["scm.review-threads", "scm.linked-work-items", "scm.issue.core"]),
				available,
			),
		/rests on searching scm\.issue\.core, scm\.linked-work-items as well: add them to evidence\.search\.consulted once you have searched them$/u,
	);
});

void test("a NOT_MET observation without a severity is refused with the scale to choose from", () => {
	for (const severity of [null, undefined]) {
		assert.throws(
			() => normalizeObservation(baseObservation({ severity })),
			/^Error: invalid severity '(?:null|undefined)' \(missing\): one of CRITICAL, MAJOR, MINOR, INFO$/u,
		);
	}
	// A word of the scale in another spelling is not that word.
	assert.throws(
		() => normalizeObservation(baseObservation({ severity: "major" })),
		/^Error: invalid severity 'major': one of CRITICAL, MAJOR, MINOR, INFO$/u,
	);
});

void test("every problem of an observation is named in one refusal", () => {
	const refusal = (raw: unknown) => {
		try {
			normalizeObservation(raw);
		} catch (error) {
			return error instanceof Error ? error.message : String(error);
		}
		return assert.fail("expected the observation to be refused");
	};
	const longSummary = "word ".repeat(40).trim();
	assert.ok(longSummary.length > MAX_SUMMARY_CHARS);
	const unrated = refusal(
		baseObservation({ severity: null, summary: longSummary, evidenceRationale: "" }),
	);
	assert.match(unrated, /^invalid severity 'null' \(missing\)/u);
	assert.match(unrated, /; also: summary must be at most/u);
	assert.match(unrated, /; also: evidenceRationale is required$/u);
	assert.match(
		refusal(baseObservation({ outcome: "PASSED", summary: "Test", evidenceRationale: " " })),
		/^invalid outcome 'PASSED': one of MET, NOT_MET, NOT_APPLICABLE, UNDETERMINED; also: summary must say what was observed as a short phrase[^;]*; also: evidenceRationale is required$/u,
	);
	// A problem of the evidence joins those of the observation.
	assert.match(
		refusal(baseObservation({ outcome: "NOT_APPLICABLE", severity: null, summary: "Test" })),
		/^summary must say what was observed[^;]*; also: a NOT_APPLICABLE observation must say why the practice does not apply/u,
	);
});
