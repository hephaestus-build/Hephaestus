import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import {
	blindCase,
	caseHash,
	parseCorpus,
	parseHumans,
	parseJudge,
	sha256,
	type Corpus,
	type EvalCase,
	type Rating,
} from "./feedback-eval/model.ts";
import { deterministicChecks, report } from "./feedback-eval/score.ts";
import { agreement, rate, workItemRate } from "./feedback-eval/statistics.ts";
import { parseJson } from "./lib/json.ts";

const corpus = parseCorpus(
	parseJson(readFileSync(new URL("feedback-eval/cases.json", import.meta.url), "utf8")),
);
const rubric = readFileSync(new URL("feedback-eval/rubric.md", import.meta.url), "utf8");
function firstCase(): EvalCase {
	const item = structuredClone(corpus.cases[0]);
	assert.ok(item);
	return item;
}
function rating(item: EvalCase): Rating {
	return {
		caseHash: caseHash(item),
		claims: item.claims.map((claim) => ({
			id: claim.id,
			verdict: claim.expected,
			grounding: claim.expectedGrounding,
			reason: claim.rationale,
			evidenceIds: claim.evidenceIds,
		})),
		quality: structuredClone(item.expectedQuality),
	};
}
function labelsFor(item: EvalCase) {
	return {
		version: 1,
		labels: ["reviewer-a", "reviewer-b"].map((reviewer) => ({ ...rating(item), reviewer })),
		adjudications: [],
	} satisfies Parameters<typeof report>[1];
}

void test("the three exact regressions preserve the false claim, true controls, and redundant follow-up", () => {
	assert.deepEqual(
		corpus.cases.map((item) => item.id),
		["t4-9", "t4-4", "t2-2"],
	);
	const item = firstCase();
	assert.equal(item.capture.producerStatus, "complete");
	assert.ok(item.capture.limitations.some((text) => text.includes("unavailable")));
	assert.equal(item.claims[0]?.expected, "false");
	assert.equal(item.claims[1]?.expected, "true");
	assert.equal(deterministicChecks(item)[0]?.status, "annotated-text-present");
	const repeated = corpus.cases.find((candidate) => candidate.id === "t2-2");
	assert.ok(repeated);
	assert.equal(repeated.claims[0]?.expected, "true");
	assert.equal(repeated.expectedQuality.repeated, true);
	assert.equal(repeated.expectedQuality.useful, false);
});

void test("absence checks abstain when their annotated region contains no contradicting bytes", () => {
	const item = firstCase();
	const claim = item.claims[0];
	assert.ok(claim?.checks[0]);
	claim.checks[0].text = "throw new IOException();";
	assert.equal(deterministicChecks(item)[0]?.status, "abstained");
});

void test("changed captured bytes and output bytes are rejected before scoring", () => {
	for (const mutate of [
		(item: EvalCase) => {
			item.output.text += " changed";
		},
		(item: EvalCase) => {
			const evidence = item.evidence[0];
			assert.ok(evidence);
			evidence.text += " changed";
		},
	]) {
		const item = firstCase();
		mutate(item);
		assert.throws(() => parseCorpus({ version: 1, cases: [item] }), /digest mismatch/u);
	}
});

void test("invalid claim spans, missing references, and split leakage cannot silently enter reports", () => {
	const item = firstCase();
	const claim = item.claims[0];
	assert.ok(claim);
	claim.quote = "not in the output";
	assert.throws(() => parseCorpus({ version: 1, cases: [item] }), /absent from output/u);
	claim.quote = item.output.text;
	claim.evidenceIds = ["missing"];
	assert.throws(() => parseCorpus({ version: 1, cases: [item] }), /unknown evidence/u);
	const variantText = `${item.output.text} variant`;
	const other = {
		...firstCase(),
		id: "held-out-copy",
		sampleId: "held-out-sample",
		split: "held-out",
		output: { text: variantText, sha256: sha256(variantText) },
	};
	assert.throws(() => parseCorpus({ version: 1, cases: [firstCase(), other] }), /crosses splits/u);
});

void test("blinded requests and their hashes do not reveal provisional verdicts, rationales, or model metadata", () => {
	const item = firstCase();
	const before = caseHash(item);
	const claim = item.claims[0];
	assert.ok(claim);
	claim.expected = "true";
	claim.expectedGrounding = "supported";
	claim.rationale = "hidden label rationale";
	item.expectedQuality.repeated = true;
	item.provenance.model = "hidden producer identity";
	assert.equal(caseHash(item), before);
	const request = blindCase(item);
	assert.deepEqual(Object.keys(request), [
		"sampleId",
		"capture",
		"reviewState",
		"evidence",
		"output",
		"claims",
	]);
	for (const text of [
		"expected",
		"rationale",
		"hidden producer identity",
		"hidden label rationale",
		"checks",
		"sampling",
	]) {
		assert.ok(!Object.keys(request).includes(text));
	}
	item.output.text += "new output";
	assert.notEqual(caseHash(item), before);
});

void test("judge import binds to rubric and evidence and refuses unknown, duplicate, or missing claims", () => {
	const item = firstCase();
	const result = {
		version: 1,
		rubricHash: sha256(rubric),
		model: "test-judge",
		run: "offline-test",
		results: [rating(item)],
	};
	assert.equal(parseJudge(result, corpus, rubric).results.length, 1);
	assert.throws(() => parseJudge(result, corpus, `${rubric} changed`), /rubric digest/u);
	assert.throws(
		() => parseJudge({ ...result, results: [rating(item), rating(item)] }, corpus, rubric),
		/Duplicate/u,
	);
	const stale = structuredClone(result);
	stale.results[0] = { ...rating(item), caseHash: "0".repeat(64) };
	assert.throws(() => parseJudge(stale, corpus, rubric), /stale case/u);
	const missing = structuredClone(result);
	missing.results[0]?.claims.pop();
	assert.throws(() => parseJudge(missing, corpus, rubric), /every selected claim/u);
});

void test("human disagreement stays visible; adjudication preserves original independent agreement", () => {
	const item = firstCase();
	const labels = labelsFor(item);
	const second = labels.labels[1]?.claims[0];
	assert.ok(second);
	second.verdict = "true";
	const data: Corpus = { version: 1, cases: [item] };
	const humans = parseHumans(labels, data);
	const before = report(data, humans);
	assert.ok(before.strata[0]);
	assert.equal(before.strata[0].human.claims.missingOrDisputed, 1);
	assert.deepEqual(before.cases[0]?.unresolvedClaims, ["c1"]);
	assert.equal(before.strata[0].humanAgreement[0]?.truth.observedAgreement, 0.5);
	humans.adjudications.push({ ...rating(item), reviewers: ["reviewer-a", "reviewer-b"] });
	const after = report(data, parseHumans(humans, data));
	assert.ok(after.strata[0]);
	assert.equal(after.strata[0].human.claims.missingOrDisputed, 0);
	assert.equal(after.strata[0].humanAgreement[0]?.truth.observedAgreement, 0.5);
	assert.equal(after.releaseDecision, "not-assessed");
});

void test("one reviewer or a fabricated adjudication cannot become human consensus", () => {
	const item = firstCase();
	const labels = labelsFor(item);
	labels.labels.pop();
	assert.equal(
		report({ version: 1, cases: [item] }, parseHumans(labels, corpus)).strata[0]?.human.claims
			.labeled,
		0,
	);
	assert.throws(
		() =>
			parseHumans(
				{
					...labels,
					adjudications: [{ ...rating(item), reviewers: ["reviewer-a", "reviewer-b"] }],
				},
				corpus,
			),
		/original independent labels/u,
	);
});

void test("failed captures are counted as runtime failures and excluded from developer quality", () => {
	const item = firstCase();
	item.capture.producerStatus = "failed";
	const scored = report({ version: 1, cases: [item] });
	assert.ok(scored.strata[0]);
	assert.equal(scored.strata[0].provisional.failedCaptures, 1);
	assert.equal(scored.strata[0].provisional.claims.selected, 0);
	assert.equal(scored.strata[0].provisional.selectedClaimTrueRate.estimate, null);
});

void test("regressions have no inferential interval and missing human labels are counted", () => {
	const scored = report(corpus);
	assert.ok(scored.strata[0]);
	assert.equal(scored.strata[0].provisional.selectedClaimTrueRate.interval95, null);
	assert.equal(scored.strata[0].human.claims.labeled, 0);
	assert.ok(scored.strata[0].human.claims.missingOrDisputed > 0);
	assert.equal(scored.judgeUse, "advisory-only");
});

void test("Wilson intervals describe binary work-item error rates including near-perfect and constant outcomes", () => {
	const rare = workItemRate(
		Array.from({ length: 30 }, (_, i) => i === 0),
		true,
	);
	assert.equal(rare.estimate, 1 / 30);
	assert.ok(rare.interval95);
	assert.ok((rare.interval95[1] ?? 0) > 0.16);
	assert.ok(Math.abs((rare.interval95[0] ?? 0) - 0.00591) < 0.00001);
	assert.ok(Math.abs((rare.interval95[1] ?? 0) - 0.1667) < 0.00001);
	assert.equal(
		workItemRate(
			Array.from({ length: 30 }, () => false),
			true,
		).interval95?.[0],
		0,
	);
	assert.equal(workItemRate([true], false).interval95, null);
	assert.equal(workItemRate([], true).estimate, null);
	assert.ok(
		(workItemRate(
			Array.from({ length: 30 }, () => false),
			true,
		).interval95?.[1] ?? 0) > 0.1,
	);
	assert.throws(() => rate([{ group: "bad", successes: 2, total: 1 }]), /Invalid/u);
});

void test("chance-corrected agreement exposes class imbalance and undefined constant labels", () => {
	assert.equal(
		agreement([
			["true", "true"],
			["true", "false"],
			["false", "true"],
			["false", "false"],
		]).kappa,
		0,
	);
	assert.equal(agreement([["true", "true"]]).kappa, null);
	assert.equal(agreement([]).observedAgreement, null);
});

void test("the credential-free CLI produces a report and blinded requests", () => {
	const cli = fileURLToPath(new URL("feedback-eval.ts", import.meta.url));
	for (const args of [[], ["--requests"]]) {
		const text = execFileSync(process.execPath, [cli, ...args], { encoding: "utf8" });
		assert.doesNotThrow(() => parseJson(text));
	}
});

void test("report intervals require a complete exhaustive held-out human overlap, never provisional or judge ratings", () => {
	const cases = Array.from({ length: 32 }, (_, i) => {
		const item = firstCase();
		item.id = `case-${i}`;
		item.sampleId = `sample-${i}`;
		item.provenance.model = "test-arm";
		item.provenance.promptSha256 = "a".repeat(64);
		item.group = `work-${i}`;
		item.split = "held-out";
		item.sampling = "random";
		item.claimCoverage = "exhaustive";
		item.output.text = `Statement A. Statement B. Sample ${i}.`;
		item.output.sha256 = sha256(item.output.text);
		item.claims = item.claims.map((claim, index) => ({
			...claim,
			quote: index === 0 ? "Statement A." : "Statement B.",
			expected: index === 0 && i % 2 === 0 ? "false" : "true",
			checks: [],
		}));
		return item;
	});
	const data = parseCorpus({ version: 1, cases });
	const labels = {
		version: 1,
		labels: cases.flatMap((item) => labelsFor(item).labels),
		adjudications: [],
	};
	const humans = parseHumans(labels, data);
	const judge = parseJudge(
		{
			version: 1,
			rubricHash: sha256(rubric),
			model: "test",
			run: "test",
			results: cases.map(rating),
		},
		data,
		rubric,
	);
	const scored = report(data, humans, judge);
	const stratum = scored.strata[0];
	assert.ok(stratum);
	assert.ok(stratum.human.workItemsWithAnyFalseClaim.interval95);
	assert.equal(stratum.human.workItemsWithAnyFalseClaim.estimate, 0.5);
	assert.equal(stratum.human.workItemsWithAnyFalseClaim.total, 32);
	assert.equal(stratum.human.selectedClaimTrueRate.interval95, null);
	assert.equal(stratum.provisional.workItemsWithAnyFalseClaim.interval95, null);
	assert.equal(stratum.judge.workItemsWithAnyFalseClaim.interval95, null);
	for (const mutate of [
		(item: EvalCase) => {
			item.split = "development";
		},
		(item: EvalCase) => {
			item.sampling = "regression";
		},
	]) {
		const descriptive = structuredClone(data);
		for (const item of descriptive.cases) {
			mutate(item);
		}
		assert.equal(
			report(descriptive, humans).strata[0]?.human.workItemsWithAnyFalseClaim.interval95,
			null,
		);
	}

	const mixed = structuredClone(data);
	assert.ok(mixed.cases[0]);
	mixed.cases[0].provenance.model = "other-arm";
	assert.equal(report(mixed, humans).strata[0]?.human.workItemsWithAnyFalseClaim.interval95, null);
	const unequal = structuredClone(data);
	assert.ok(unequal.cases[0]);
	const repeated = {
		...structuredClone(unequal.cases[0]),
		id: "repeat",
		sampleId: "repeated-sample",
	};
	unequal.cases.push(repeated);
	const repeatedLabels = parseHumans(
		{ ...humans, labels: [...humans.labels, ...labelsFor(repeated).labels] },
		unequal,
	);
	assert.equal(
		report(unequal, repeatedLabels).strata[0]?.human.workItemsWithAnyFalseClaim.interval95,
		null,
	);
	const selected = structuredClone(data);
	assert.ok(selected.cases[0]);
	selected.cases[0].claimCoverage = "selected";
	assert.equal(
		report(selected, humans).strata[0]?.human.workItemsWithAnyFalseClaim.interval95,
		null,
	);
	const missing = structuredClone(humans);
	missing.labels.pop();
	assert.equal(report(data, missing).strata[0]?.human.workItemsWithAnyFalseClaim.interval95, null);
	const disputed = structuredClone(humans);
	const claim = disputed.labels[0]?.claims[0];
	assert.ok(claim);
	claim.verdict = "unsupported";
	assert.equal(report(data, disputed).strata[0]?.human.workItemsWithAnyFalseClaim.interval95, null);
});

void test("literal checks report annotated text presence even inside comments and refuse summary evidence", () => {
	const item = firstCase();
	const evidence = item.evidence[0];
	const check = item.claims[0]?.checks[0];
	assert.ok(evidence && check);
	evidence.text = "// return 0;";
	evidence.sha256 = sha256(evidence.text);
	check.region = evidence.text;
	assert.equal(
		deterministicChecks(parseCorpus({ version: 1, cases: [item] }).cases[0] ?? item)[0]?.status,
		"annotated-text-present",
	);
	evidence.role = "compaction";
	assert.throws(() => parseCorpus({ version: 1, cases: [item] }), /source evidence/u);
});

void test("reference-only evidence cannot establish supported producer grounding", () => {
	const item = firstCase();
	const humans = labelsFor(item);
	for (const label of humans.labels) {
		const claim = label.claims[0];
		assert.ok(claim);
		claim.grounding = "supported";
		claim.evidenceIds = ["e2"];
	}
	assert.throws(() => parseHumans(humans, corpus), /producer evidence/u);
});

void test("partial adjudication resolves one claim while preserving other disputes", () => {
	const item = firstCase();
	const labels = labelsFor(item);
	for (const claim of labels.labels[1]?.claims ?? []) {
		claim.verdict = "unsupported";
	}
	for (const label of labels.labels) {
		label.quality.useful = true;
	}
	const adjudicated = rating(item);
	adjudicated.claims = adjudicated.claims.slice(0, 1);
	const humans = parseHumans(
		{ ...labels, adjudications: [{ ...adjudicated, reviewers: ["reviewer-a", "reviewer-b"] }] },
		corpus,
	);
	const scored = report({ version: 1, cases: [item] }, humans);
	assert.ok(scored.strata[0]);
	assert.equal(scored.strata[0].human.quality.useful?.rateTrue.estimate, 1);
	assert.deepEqual(scored.cases[0]?.unresolvedClaims, ["c2"]);
	assert.equal(scored.strata[0].human.claims.missingOrDisputed, 1);
});

void test("identical outputs from repeated attempts remain distinct samples in one work item", () => {
	const first = firstCase();
	const second = { ...structuredClone(first), id: "repeat", sampleId: "second-sample" };
	const data = parseCorpus({ version: 1, cases: [first, second] });
	assert.notEqual(caseHash(first), caseHash(second));
	const scored = report(data);
	assert.ok(scored.strata[0]);
	assert.equal(scored.strata[0].provisional.cases, 2);
	assert.equal(scored.strata[0].provisional.workItemsWithAnyFalseClaim.total, 1);
});

void test("a null quality label against a value is recorded as disagreement and unresolved quality", () => {
	const item = firstCase();
	const labels = labelsFor(item);
	assert.ok(labels.labels[0]);
	labels.labels[0].quality.relevant = true;
	const scored = report({ version: 1, cases: [item] }, parseHumans(labels, corpus));
	assert.ok(scored.strata[0]);
	assert.equal(scored.strata[0].humanAgreement[0]?.quality.relevant?.observedAgreement, 0);
	assert.equal(scored.strata[0].human.quality.relevant?.missingOrDisputed, 1);
});

void test("unsettled truth and unknown grounding cannot count as evidence that a work item has no error", () => {
	const item = firstCase();
	for (const claim of item.claims) {
		claim.expected = "unsupported";
		claim.expectedGrounding = "unknown";
	}
	const scored = report(
		{ version: 1, cases: [item] },
		parseHumans(labelsFor(item), { version: 1, cases: [item] }),
	);
	const human = scored.strata[0]?.human;
	assert.ok(human);
	assert.equal(human.workItemsWithAnyFalseClaim.estimate, null);
	assert.equal(human.workItemsWithAnyFalseClaim.missingOrDisputedWorkItems, 1);
	assert.equal(human.workItemsWithAnyUnsupportedClaim.estimate, null);
	assert.equal(human.workItemsWithAnyUnsupportedClaim.missingOrDisputedWorkItems, 1);
});
