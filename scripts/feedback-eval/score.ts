import {
	caseHash,
	type Corpus,
	type EvalCase,
	type HumanLabels,
	type JudgeResults,
	type Quality,
	type Rating,
} from "./model.ts";
import { agreement, rate, workItemRate } from "./statistics.ts";

export function deterministicChecks(item: EvalCase) {
	return item.claims.flatMap((claim) =>
		claim.checks.map((check) => {
			const present = check.region.includes(check.text);
			let status: "annotated-text-present" | "matched" | "abstained" = "abstained";
			if (check.kind === "literal-absence" && present) {
				status = "annotated-text-present";
			}
			if (check.kind === "literal-quote" && present) {
				status = "matched";
			}
			return { claimId: claim.id, kind: check.kind, evidenceId: check.evidenceId, status };
		}),
	);
}

const qualityKeys = ["relevant", "repeated", "useful", "withholdingCorrect"] as const;

function consensus(item: EvalCase, humans: HumanLabels): Rating | undefined {
	const adjudicated = humans.adjudications.find((rating) => rating.caseHash === caseHash(item));
	const labels = humans.labels.filter((label) => label.caseHash === caseHash(item));
	if (labels.length < 2) {
		return undefined;
	}
	const [first] = labels;
	if (first === undefined) {
		return undefined;
	}
	const claims = first.claims.filter((claim) =>
		labels.every((label) =>
			label.claims.some(
				(other) =>
					other.id === claim.id &&
					other.verdict === claim.verdict &&
					other.grounding === claim.grounding,
			),
		),
	);
	const quality: Quality = {
		relevant: null,
		repeated: null,
		useful: null,
		withholdingCorrect: null,
	};
	for (const key of qualityKeys) {
		if (labels.every((label) => label.quality[key] === first.quality[key])) {
			quality[key] = first.quality[key];
		}
	}
	for (const key of qualityKeys) {
		quality[key] = adjudicated?.quality[key] ?? quality[key];
	}
	return {
		caseHash: caseHash(item),
		claims: item.claims.flatMap((claim) => {
			const resolved =
				adjudicated?.claims.find((candidate) => candidate.id === claim.id) ??
				claims.find((candidate) => candidate.id === claim.id);
			return resolved === undefined ? [] : [resolved];
		}),
		quality,
	};
}

function expectedRating(item: EvalCase): Rating {
	return {
		caseHash: caseHash(item),
		claims: item.claims.map((claim) => ({
			id: claim.id,
			verdict: claim.expected,
			grounding: claim.expectedGrounding,
			reason: claim.rationale,
			evidenceIds: claim.evidenceIds,
		})),
		quality: item.expectedQuality,
	};
}

function summarize(
	cases: EvalCase[],
	ratingFor: (item: EvalCase) => Rating | undefined,
	allowInference = false,
) {
	const eligible = cases.filter(
		(item) => item.capture.producerStatus !== "failed" && item.capture.status !== "failed",
	);
	const ratings = eligible.map((item) => ({ item, rating: ratingFor(item) }));
	const allClaims = eligible.reduce((sum, item) => sum + item.claims.length, 0);
	const labeledClaims = ratings.reduce((sum, { rating }) => sum + (rating?.claims.length ?? 0), 0);
	const repeatCounts = [...new Set(eligible.map((item) => item.group))].map(
		(group) => eligible.filter((item) => item.group === group).length,
	);
	const oneKnownArm =
		eligible.every(
			(item) => item.provenance.model !== null && item.provenance.promptSha256 !== null,
		) &&
		new Set(
			eligible.map((item) => JSON.stringify([item.provenance.model, item.provenance.promptSha256])),
		).size === 1;
	const infer =
		allowInference &&
		oneKnownArm &&
		new Set(repeatCounts).size === 1 &&
		cases.every(
			(item) =>
				item.split === "held-out" &&
				item.sampling === "random" &&
				item.claimCoverage === "exhaustive",
		) &&
		labeledClaims === allClaims;
	function metric(predicate: (claim: Rating["claims"][number]) => boolean) {
		return rate(
			ratings.map(({ item, rating }) => ({
				group: item.group,
				successes: rating?.claims.filter(predicate).length ?? 0,
				total: rating?.claims.length ?? 0,
			})),
		);
	}
	const quality = Object.fromEntries(
		qualityKeys.map((key) => {
			const labeled = ratings.filter(
				({ rating }) => rating?.quality[key] !== null && rating?.quality[key] !== undefined,
			);
			return [
				key,
				{
					rateTrue: rate(
						labeled.map(({ item, rating }) => ({
							group: item.group,
							successes: rating?.quality[key] === true ? 1 : 0,
							total: 1,
						})),
					),
					missingOrDisputed: eligible.length - labeled.length,
				},
			];
		}),
	);
	const groups = [...new Set(eligible.map((item) => item.group))];
	function anyError(
		predicate: (claim: Rating["claims"][number]) => boolean,
		unsettled: (claim: Rating["claims"][number]) => boolean,
	) {
		const complete = groups
			.map((group) => ratings.filter(({ item }) => item.group === group))
			.filter((group) =>
				group.every(({ item, rating }) => rating?.claims.length === item.claims.length),
			);
		const outcomes = complete
			.map((group) => {
				const claims = group.flatMap(({ rating }) => rating?.claims ?? []);
				if (claims.some(predicate)) {
					return true;
				}
				return claims.some(unsettled) ? undefined : false;
			})
			.filter((outcome) => outcome !== undefined);
		return {
			...workItemRate(outcomes, infer && outcomes.length === groups.length),
			missingOrDisputedWorkItems: groups.length - outcomes.length,
		};
	}

	return {
		cases: cases.length,
		failedCaptures: cases.filter(
			(item) => item.capture.producerStatus === "failed" || item.capture.status === "failed",
		).length,
		partialCaptures: cases.filter((item) => item.capture.status === "partial").length,
		withheld: cases.filter((item) => item.reviewState === "withheld").length,
		notReviewed: cases.filter((item) => item.reviewState === "not-reviewed").length,
		claims: {
			selected: allClaims,
			labeled: labeledClaims,
			missingOrDisputed: allClaims - labeledClaims,
		},
		selectedClaimTrueRate: metric((claim) => claim.verdict === "true"),
		selectedClaimFalseRate: metric((claim) => claim.verdict === "false"),
		selectedClaimUnsettledTruthRate: metric((claim) => claim.verdict === "unsupported"),
		selectedClaimUnsupportedRate: metric((claim) => claim.grounding === "unsupported"),
		selectedClaimUnknownGroundingRate: metric((claim) => claim.grounding === "unknown"),
		quality,
		workItemsWithAnyFalseClaim: anyError(
			(claim) => claim.verdict === "false",
			(claim) => claim.verdict === "unsupported",
		),
		workItemsWithAnyUnsupportedClaim: anyError(
			(claim) => claim.grounding === "unsupported",
			(claim) => claim.grounding === "unknown",
		),
	};
}

function judgeAgreement(cases: EvalCase[], humans: HumanLabels, judge: JudgeResults | undefined) {
	const confusion = {
		true: { true: 0, false: 0, unsupported: 0 },
		false: { true: 0, false: 0, unsupported: 0 },
		unsupported: { true: 0, false: 0, unsupported: 0 },
	};
	const pairs: [string, string][] = [];
	const grounding: [string, string][] = [];
	for (const item of cases.filter(
		(candidate) =>
			candidate.capture.producerStatus !== "failed" && candidate.capture.status !== "failed",
	)) {
		const human = consensus(item, humans);
		const graded = judge?.results.find((rating) => rating.caseHash === caseHash(item));
		for (const claim of human?.claims ?? []) {
			const other = graded?.claims.find((candidate) => candidate.id === claim.id);
			if (other !== undefined) {
				confusion[claim.verdict][other.verdict] += 1;
				pairs.push([claim.verdict, other.verdict]);
				grounding.push([claim.grounding, other.grounding]);
			}
		}
	}
	return {
		confusionHumanRowsJudgeColumns: confusion,
		truth: agreement(pairs),
		grounding: agreement(grounding),
	};
}

function humanAgreement(cases: EvalCase[], humans: HumanLabels) {
	const reviewers = [...new Set(humans.labels.map((label) => label.reviewer))].toSorted();
	return reviewers.flatMap((left, index) =>
		reviewers.slice(index + 1).map((right) => {
			const truth: [string, string][] = [];
			const grounding: [string, string][] = [];
			const qualityPairs: Record<keyof Quality, [string, string][]> = {
				relevant: [],
				repeated: [],
				useful: [],
				withholdingCorrect: [],
			};
			for (const item of cases.filter(
				(candidate) =>
					candidate.capture.producerStatus !== "failed" && candidate.capture.status !== "failed",
			)) {
				const a = humans.labels.find(
					(label) => label.caseHash === caseHash(item) && label.reviewer === left,
				);
				const b = humans.labels.find(
					(label) => label.caseHash === caseHash(item) && label.reviewer === right,
				);
				if (a === undefined || b === undefined) {
					continue;
				}
				for (const claim of a.claims) {
					const other = b.claims.find((candidate) => candidate.id === claim.id);
					if (other !== undefined) {
						truth.push([claim.verdict, other.verdict]);
						grounding.push([claim.grounding, other.grounding]);
					}
				}
				for (const key of qualityKeys) {
					qualityPairs[key].push([String(a.quality[key]), String(b.quality[key])]);
				}
			}
			return {
				reviewers: [left, right],
				truth: agreement(truth),
				grounding: agreement(grounding),
				quality: Object.fromEntries(qualityKeys.map((key) => [key, agreement(qualityPairs[key])])),
			};
		}),
	);
}

export function report(corpus: Corpus, humanLabels?: HumanLabels, judge?: JudgeResults) {
	const humans = humanLabels ?? { version: 1, labels: [], adjudications: [] };
	const keys = [
		...new Set(
			corpus.cases.map((item) =>
				JSON.stringify([item.practice, item.destination, item.split, item.sampling]),
			),
		),
	].toSorted();
	return {
		version: 1,
		releaseDecision: "not-assessed",
		judgeUse: "advisory-only",
		samplingValidation:
			"Owner-attested metadata; random selection and exhaustive decomposition are not verified by this harness",
		uncertainty:
			"Regression cases have descriptive counts only. Random held-out exhaustive human labels can have Wilson intervals for binary work-item error rates, never claim precision. No paired release comparison or known-problem recall is performed.",
		strata: keys.map((key) => {
			const cases = corpus.cases.filter(
				(item) =>
					JSON.stringify([item.practice, item.destination, item.split, item.sampling]) === key,
			);
			return {
				practice: cases[0]?.practice,
				destination: cases[0]?.destination,
				split: cases[0]?.split,
				sampling: cases[0]?.sampling,
				provisional: summarize(cases, expectedRating),
				human: summarize(cases, (item) => consensus(item, humans), true),
				judge: summarize(cases, (item) =>
					judge?.results.find((rating) => rating.caseHash === caseHash(item)),
				),
				judgeHumanAgreement: judgeAgreement(cases, humans, judge),
				humanAgreement: humanAgreement(cases, humans),
			};
		}),
		cases: corpus.cases.map((item) => ({
			id: item.id,
			caseHash: caseHash(item),
			split: item.split,
			capture: item.capture,
			checks: deterministicChecks(item),
			humanReviewers: humans.labels
				.filter((label) => label.caseHash === caseHash(item))
				.map((label) => label.reviewer),
			unresolvedClaims: item.claims
				.filter(
					(claim) =>
						consensus(item, humans)?.claims.some((candidate) => candidate.id === claim.id) !== true,
				)
				.map((claim) => claim.id),
		})),
	};
}
