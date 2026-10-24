import type {
	EvidenceCitation,
	FeedbackCopy,
	ItemRef,
	Problem,
	ProblemSummary,
	ReviewBoundFeedback,
	ReviewBoundResult,
	ReviewDetail,
	ReviewFeedbackDetail,
	ReviewProblemDetail,
	ReviewResultDetail,
	ReviewLineage,
	ReviewPractice,
	ReviewRef,
} from "@/api/types.gen";

import {
	boundFeedbackOf,
	boundResultOf,
	feedbackDetail,
	feedbackCounts,
	gitlabMergeRequest,
	outlineDocument,
	practiceNameOf,
	pullRequestReviewItem,
	reviewAccounts,
	resultDetail,
	resultCounts,
	reviewProblemDetail,
	reviewDetail,
	reviewRefOf,
	reviewDetailScanManyJobs,
	reviewDevelopers,
	reviewItem,
	scannedFiles,
	scannedRepository,
	slackConversation,
} from "./fixtures";
import {
	at,
	dayAt,
	EARLIER_REVIEW_ID,
	LINEAGE_REVIEW_ID,
	LINEAGE_RELATION,
	lineageKey,
	lineageEdge,
	type LineageFeedbackSpec,
	type LineageResultSpec,
	storyLineage,
} from "./lineage-fixtures";

export interface LineageStateCase {
	key: string;
	title: string;
	lineage: ReviewLineage;
	review?: ReviewDetail;
}

export type LineageStateGroup =
	| "review"
	| "origin"
	| "item"
	| "results"
	| "destination"
	| "delivery"
	| "volume";

const id = (group: number, index: number) =>
	`5e5e${String(group).padStart(4, "0")}-0000-4000-8000-${String(index).padStart(12, "0")}`;

const RESULT_A = id(1, 1);
const RESULT_B = id(1, 2);
const RESULT_C = id(1, 3);
const FEEDBACK_A = id(2, 1);

function stateResult(
	resultId: string,
	overrides: Partial<ReviewBoundResult> = {},
	problems: ProblemSummary[] = [],
): LineageResultSpec {
	const practiceSlug = overrides.practiceSlug ?? "errors-carry-context";
	return {
		result: boundResultOf(
			resultDetail({
				id: resultId,
				practiceSlug,
				practiceName: practiceNameOf(practiceSlug),
				...overrides,
			}),
		),
		problems,
	};
}

const problemOf = (resultId: string, position: number, severity: ProblemSummary["severity"]) => ({
	id: `${resultId.slice(0, -2)}${String(position).padStart(2, "0")}`,
	severity,
	summary: `Problem ${position + 1}`,
});

const threeResults = (overrides: Partial<ReviewBoundResult> = {}): LineageResultSpec[] => [
	stateResult(RESULT_A, overrides, [
		problemOf(RESULT_A, 0, "MAJOR"),
		problemOf(RESULT_A, 1, "MINOR"),
	]),
	stateResult(RESULT_B, {
		practiceSlug: "thin-controllers",
		outcome: "MET",
		severity: null,
		summary: "The controller delegates before it does anything else",
		recordedAt: at("13:40:00"),
	}),
	stateResult(RESULT_C, {
		practiceSlug: "the-change-explains-itself",
		outcome: "DOES_NOT_APPLY",
		severity: null,
		summary: "The change is a dependency bump with nothing to explain",
		recordedAt: at("13:40:20"),
	}),
];

function stateFeedback(
	feedbackId: string,
	basis: string[],
	overrides: Partial<ReviewBoundFeedback> = {},
): LineageFeedbackSpec {
	return {
		feedback: boundFeedbackOf(
			feedbackDetail({
				id: feedbackId,
				state: "DELIVERED",
				deliveredAt: at("13:41:30"),
				...overrides,
			}),
		),
		basis,
	};
}

const oneComment = (overrides: Partial<ReviewBoundFeedback> = {}) =>
	stateFeedback(FEEDBACK_A, [RESULT_A], overrides);

const baseReview = reviewRefOf(
	reviewDetail({ id: LINEAGE_REVIEW_ID, completedAt: at("13:44:00") }),
);

interface ChainSpec {
	item?: ItemRef;
	review?: Partial<ReviewRef> | null;
	results?: LineageResultSpec[];
	feedback?: LineageFeedbackSpec[];
}

function chain({
	item = reviewItem,
	review = {},
	results,
	feedback,
}: ChainSpec = {}): ReviewLineage {
	const ref = review === null ? null : { ...baseReview, item, ...review };
	const resultsOf = (results ?? threeResults()).map((entry) => ({
		...entry,
		result: {
			...entry.result,
			item: entry.result.item.id === reviewItem.id ? item : entry.result.item,
		},
	}));
	return storyLineage({
		root: ref === null ? { kind: "ITEM", id: item.id } : { kind: "REVIEW", id: ref.id },
		item,
		reviews: ref === null ? [] : [ref],
		results: resultsOf,
		feedback: feedback ?? [oneComment()],
	});
}

function detailOf(lineage: ReviewLineage, overrides: Partial<ReviewDetail> = {}): ReviewDetail {
	const ref = lineage.nodes.find((entry) => entry.review !== null)?.review ?? baseReview;
	return reviewDetail({
		id: ref.id,
		item: ref.item,
		origin: ref.origin,
		status: ref.status,
		createdAt: ref.createdAt,
		orderedAt: ref.createdAt,
		completedAt: ref.completedAt,
		...overrides,
	});
}

const withCase = (
	key: string,
	title: string,
	lineage: ReviewLineage,
	review?: (lineage: ReviewLineage) => ReviewDetail,
): LineageStateCase => ({ key, title, lineage, review: review?.(lineage) });

const withheldPractice: ReviewPractice = {
	practiceSlug: "product-language",
	practiceName: practiceNameOf("product-language"),
	practiceVersionId: "201",
	number: 1,
	status: "WITHHELD",
	reason: "The practice reads the review threads, and the provider did not return them.",
	itemCounts: { listed: 1, reviewed: 0, reused: 0, withheld: 1, notReviewed: 0 },
	results: { met: 0, notMet: 0, doesNotApply: 0, couldNotDetermine: 0 },
	script: null,
};

const reviewCases: LineageStateCase[] = [
	withCase(
		"queued",
		"Queued",
		chain({ review: { status: "QUEUED", completedAt: null }, results: [], feedback: [] }),
	),
	withCase(
		"running",
		"Running, two observations so far",
		chain({
			review: { status: "RUNNING", completedAt: null },
			results: threeResults().slice(0, 2),
			feedback: [],
		}),
	),
	withCase("complete", "Complete", chain()),
	withCase(
		"failed",
		"Failed after recording one observation",
		chain({ review: { status: "FAILED" }, results: threeResults().slice(0, 1), feedback: [] }),
		(lineage) => detailOf(lineage, { stopReason: "EXECUTION_FAILED" }),
	),
	withCase(
		"timed-out",
		"Timed out with nothing recorded",
		chain({ review: { status: "FAILED", completedAt: at("13:55:10") }, results: [], feedback: [] }),
		(lineage) => detailOf(lineage, { stopReason: "TIMED_OUT" }),
	),
	withCase(
		"cancelled",
		"Canceled by an admin",
		chain({
			review: { status: "CANCELLED", completedAt: at("13:36:00") },
			results: [],
			feedback: [],
		}),
		(lineage) => detailOf(lineage, { stopReason: "CANCELLED_BY_ADMIN" }),
	),
	withCase(
		"withheld-practice",
		"A practice withheld, with no observation",
		chain({ results: threeResults().slice(0, 2) }),
		(lineage) => detailOf(lineage, { practices: [...reviewDetail().practices, withheldPractice] }),
	),
	withCase(
		"expired-runtime",
		"Execution details expired",
		chain({
			review: { completedAt: dayAt("05-02", "13:44:00"), createdAt: dayAt("05-02", "13:35:00") },
		}),
		(lineage) => detailOf(lineage, { runtime: null, stageTiming: [] }),
	),
	{
		key: "scan-many-jobs",
		title: "A scan carried by four jobs",
		lineage: chain({
			item: scannedRepository,
			review: {
				id: reviewDetailScanManyJobs.id,
				kind: "SCAN",
				status: "PARTIAL",
				origin: "REQUEST",
				itemVersion: null,
			},
			results: scannedFiles.map((file, index) =>
				stateResult(id(3, index + 1), {
					reviewId: reviewDetailScanManyJobs.id,
					item: file,
					developer: null,
					outcome: index === 0 ? "NOT_MET" : "MET",
					severity: index === 0 ? "MAJOR" : null,
					summary: index === 0 ? "The give-up line names no event" : null,
				}),
			),
			feedback: [],
		}),
		review: reviewDetailScanManyJobs,
	},
	withCase("record-gone", "The record of the review is gone", chain({ review: null })),
];

const originCases: LineageStateCase[] = [
	withCase("origin-event", "Started by an event", chain()),
	withCase(
		"origin-request",
		"Requested by an admin",
		chain({ review: { origin: "REQUEST" } }),
		(lineage) => detailOf(lineage, { startedBy: null, requestedBy: reviewAccounts.grace }),
	),
	withCase(
		"origin-schedule",
		"Started by a schedule",
		chain({ review: { origin: "SCHEDULE" } }),
		(lineage) => detailOf(lineage, { startedBy: null }),
	),
	withCase(
		"origin-backfill",
		"One of a batch over past work",
		chain({
			review: { origin: "BACKFILL" },
			feedback: [oneComment({ state: "WITHHELD", deliveredAt: null })],
		}),
		(lineage) =>
			detailOf(lineage, { startedBy: null, batchId: "0b0b0b0b-1111-4111-8111-111111111111" }),
	),
];

const itemCases: LineageStateCase[] = [
	withCase("item-github", "A pull request on GitHub", chain()),
	withCase("item-gitlab", "A merge request on GitLab", chain({ item: gitlabMergeRequest })),
	withCase(
		"item-slack",
		"A Slack thread",
		chain({
			item: slackConversation,
			feedback: [oneComment({ destination: "CONVERSATION" })],
		}),
	),
	withCase("item-outline", "An Outline document", chain({ item: outlineDocument })),
	withCase(
		"item-pull-request-review",
		"A review of a pull request, under its pull request",
		chain({
			item: pullRequestReviewItem,
			results: [
				stateResult(RESULT_A, {
					practiceSlug: "review-comments-explain",
					outcome: "MET",
					severity: null,
					developer: reviewDevelopers.grace,
					summary: "Each comment says what to change and why",
				}),
			],
			feedback: [
				oneComment({ destination: "PRACTICE_PAGE", state: "WITHHELD", deliveredAt: null }),
			],
		}),
	),
];

const outcomeGrid: LineageResultSpec[] = [
	...(["CRITICAL", "MAJOR", "MINOR", "TRIVIAL"] as const).map((severity, index) =>
		stateResult(
			id(4, index + 1),
			{
				summary: `Not met, ${severity.toLowerCase()}`,
				severity,
				recordedAt: at(`13:3${6 + (index % 3)}:00`),
			},
			[problemOf(id(4, index + 1), 0, severity)],
		),
	),
	stateResult(id(4, 5), {
		summary: "Met",
		outcome: "MET",
		severity: null,
		practiceSlug: "thin-controllers",
		recordedAt: at("13:39:00"),
	}),
	stateResult(id(4, 6), {
		summary: null,
		outcome: "MET",
		severity: null,
		practiceSlug: "tests-name-the-behaviour",
		recordedAt: at("13:39:05"),
	}),
	stateResult(id(4, 7), {
		summary: "Does not apply",
		outcome: "DOES_NOT_APPLY",
		severity: null,
		practiceSlug: "the-change-explains-itself",
		recordedAt: at("13:39:10"),
	}),
	stateResult(id(4, 8), {
		summary: "Could not determine whether the migration runs first",
		outcome: "COULD_NOT_DETERMINE",
		severity: null,
		practiceSlug: "product-language",
		recordedAt: at("13:39:20"),
	}),
];

const RICH_OUTCOMES = [
	"MET",
	"NOT_MET",
	"MET",
	"DOES_NOT_APPLY",
	"COULD_NOT_DETERMINE",
	"MET",
	"NOT_MET",
	"MET",
	"DOES_NOT_APPLY",
	"COULD_NOT_DETERMINE",
	"NOT_MET",
	"MET",
	"MET",
	"NOT_MET",
	"DOES_NOT_APPLY",
	"COULD_NOT_DETERMINE",
	"MET",
	"NOT_MET",
	"MET",
	"DOES_NOT_APPLY",
	"NOT_MET",
	"COULD_NOT_DETERMINE",
	"MET",
	"MET",
] as const;

const RICH_PRACTICES = [
	"Validate input at the boundary",
	"Errors carry context",
	"Keep controllers thin",
	"Name tests after behaviour",
	"Document configuration changes",
	"Keep changes focused",
	"Distinguish failure causes",
	"Use product language",
	"Explain dependency upgrades",
	"Make retries bounded",
	"Preserve the original cause",
	"Keep transactions small",
	"Make permissions explicit",
	"Make failures actionable",
	"Record architectural decisions",
	"Explain operational limits",
	"Keep side effects at the boundary",
	"Test failure paths",
	"Name the reviewed work",
	"Document compatibility",
	"Handle missing configuration",
	"Capture external failures",
	"Keep cleanup deterministic",
	"Explain the change",
] as const;

const RICH_PROBLEM_COUNTS = [3, 3, 2, 2, 1, 1];

function richCitation(index: number, document: boolean): EvidenceCitation {
	return {
		sourceKind: document ? "scm.repository.file" : "scm.pull-request.diff",
		path: document ? "docs/operations/review-recovery.md" : `src/reviews/Boundary${index}.ts`,
		sourcePath: null,
		startLine: document ? 8 : 21,
		endLine: document ? 8 : 21,
		side: document ? null : "NEW",
		commit: null,
		quote: document
			? "Retry the operation after checking the configured endpoint."
			: 'throw new Error("Request failed");',
		verification: null,
	};
}

function richResults(firstRecordedAt: Date): ReviewResultDetail[] {
	let notMet = 0;
	return RICH_OUTCOMES.map((outcome, index) => {
		const resultId = id(5, index + 1);
		const practiceName = RICH_PRACTICES[index] ?? "Review the boundary";
		let problemCount = 0;
		if (outcome === "NOT_MET") {
			problemCount = RICH_PROBLEM_COUNTS[notMet] ?? 0;
			notMet += 1;
		}
		const problems: Problem[] = Array.from({ length: problemCount }, (_, position) => ({
			id: id(11, (index + 1) * 10 + position),
			resultId,
			position,
			key: `rich-${index + 1}-${position + 1}`,
			severity: position === 0 ? "MAJOR" : "MINOR",
			summary:
				position === 0
					? "The failure does not name the operation"
					: `Recovery step ${position + 1} is missing for boundary ${index + 1}`,
			explanation: `Boundary ${index + 1}, checkpoint ${position + 1}: the caller cannot distinguish a missing endpoint from an unavailable provider.`,
			nextStep: `Name the failed operation and add the recovery step for boundary ${index + 1}, checkpoint ${position + 1}.`,
			citations: [richCitation(index + 1, position === 2)],
			basis: null,
		}));
		return resultDetail({
			id: resultId,
			reviewId: LINEAGE_REVIEW_ID,
			item: gitlabMergeRequest,
			practiceSlug: `rich-practice-${index + 1}`,
			practiceName,
			practiceVersionId: String(920_001 + index),
			practiceVersionNumber: 1,
			outcome,
			severity: problems[0]?.severity ?? null,
			summary:
				index === 1 || index === 6
					? "Failures lose their context"
					: `${practiceName}: reviewed boundary ${index + 1}`,
			explanation: `This observation concerns boundary ${index + 1}. The captured passage supports the recorded outcome for this practice.`,
			recordedAt: new Date(firstRecordedAt.getTime() + index * 10_000),
			problems,
			evidence: {
				citations: outcome === "MET" ? [richCitation(index + 1, false)] : [],
				openQuestions: [],
				inapplicability: null,
				search: null,
			},
			marks: [],
			disagreements: [],
			feedback: [],
			acceptedAt: null,
			markedIncorrectAt: null,
			example: null,
			coverage: null,
		});
	});
}

/** One deterministic reading fixture; every list, detail and trace projects these same records. */
export const richReviewFixture = (() => {
	const createdAt = at("13:35:00");
	const completedAt = at("13:40:00");
	const results = richResults(new Date(createdAt.getTime() + 60_000));
	const notMet = results.filter((result) => result.outcome === "NOT_MET");
	const lateMet = results.at(-1);
	if (lateMet === undefined || lateMet.outcome !== "MET") {
		throw new globalThis.Error("The rich review must include a late Met result");
	}
	const destinations = [
		"ON_WORK",
		"ON_WORK",
		"PRACTICE_PAGE",
		"PRACTICE_PAGE",
		"CONVERSATION",
		"CONVERSATION",
	] as const;
	const states = [
		"DELIVERED",
		"WITHHELD",
		"DELIVERED",
		"FAILED",
		"DELIVERED",
		"DELIVERED",
	] as const;
	const feedback = notMet.map((result, index): ReviewFeedbackDetail => {
		const sources = index === 0 ? [result, ...notMet.slice(1, 2)] : [result];
		if (index === notMet.length - 1) {
			sources.push(lateMet);
		}
		const basis = sources
			.flatMap((source) => [
				{ resultId: source.id, problemId: null, result: boundResultOf(source) },
				...source.problems.map((problem) => ({
					resultId: source.id,
					problemId: problem.id,
					result: boundResultOf(source),
				})),
			])
			.map((entry, ordinal) => ({ ...entry, ordinal }));
		const destination = destinations[index] ?? "ON_WORK";
		const state = states[index] ?? "DELIVERED";
		const composedAt = new Date(completedAt.getTime() + index * 60_000);
		const deliveredAt = state === "DELIVERED" ? new Date(composedAt.getTime() + 30_000) : null;
		let body = `Name the failed operation at boundary ${results.indexOf(result) + 1}, then explain its recovery step.`;
		if (index === notMet.length - 1) {
			body += ` Keep the explanation of the change at boundary ${results.indexOf(lateMet) + 1}, which this review recorded as Met.`;
		}
		const copy: FeedbackCopy = {
			id: id(12, index + 1),
			kind: destination === "CONVERSATION" ? "CHAT_TURN" : "SUMMARY",
			position: 0,
			body,
			state: ({ DELIVERED: "POSTED", WITHHELD: "WITHHELD", FAILED: "FAILED" } as const)[state],
			postedAt: deliveredAt,
			writeStartedAt: state === "WITHHELD" ? null : new Date(composedAt.getTime() + 10_000),
			externalRef: null,
			externalUrl: null,
			mentorEntryId: null,
			path: null,
			startLine: null,
			endLine: null,
			side: null,
			readback: null,
			lastError: state === "FAILED" ? "The destination was unavailable." : null,
			stopReason: state === "WITHHELD" ? "WORKSPACE_DELIVERY_PAUSED" : null,
		};
		return feedbackDetail({
			id: id(6, index + 1),
			reviewId: LINEAGE_REVIEW_ID,
			item: gitlabMergeRequest,
			destination,
			state,
			deliveredAt,
			basis,
			location: (
				{
					ON_WORK: "GITLAB",
					PRACTICE_PAGE: "PRACTICE_PAGES",
					CONVERSATION: "NEXT_CONVERSATION",
				} as const
			)[destination],
			body,
			contentDigest: null,
			bodyPreview: body,
			bodyTruncated: false,
			resultCount: sources.length,
			stopReason: state === "WITHHELD" ? "WORKSPACE_DELIVERY_PAUSED" : null,
			copies: [copy],
			sends: [],
			decisions: [],
			checks: [],
			response: null,
			copyCounts: {
				planned: 0,
				writeStarted: 0,
				posted: copy.state === "POSTED" ? 1 : 0,
				failed: copy.state === "FAILED" ? 1 : 0,
				withheld: copy.state === "WITHHELD" ? 1 : 0,
				retracted: 0,
			},
			createdAt: composedAt,
			disagreedAt: null,
			replacesId: null,
			withdrawn: false,
			textHidden: false,
			unverifiedHistory: false,
		});
	});
	for (const result of results) {
		result.feedback = feedback
			.filter((entry) => entry.basis.some((basis) => basis.resultId === result.id))
			.map(boundFeedbackOf);
	}
	const problems: ReviewProblemDetail[] = results.flatMap((result) =>
		result.problems.map((problem) => {
			const [citation] = problem.citations;
			return {
				...reviewProblemDetail,
				id: problem.id,
				resultId: result.id,
				reviewId: result.reviewId,
				item: result.item,
				developer: result.developer,
				practiceSlug: result.practiceSlug,
				practiceName: result.practiceName,
				group: null,
				recordedAt: result.recordedAt,
				severity: problem.severity,
				summary: problem.summary,
				evidenceHidden: false,
				path: citation?.path ?? null,
				sourceKind: citation?.sourceKind ?? null,
				startLine: citation?.startLine ?? null,
				endLine: citation?.endLine ?? null,
				side: citation?.side ?? null,
				acceptedAt: null,
				markedIncorrectAt: null,
				disagreedAt: null,
				result: boundResultOf(result),
				problem,
			};
		}),
	);
	const review = reviewDetail({
		createdAt,
		orderedAt: createdAt,
		completedAt,
		item: gitlabMergeRequest,
		results: resultCounts(results.map((result) => result.outcome)),
		feedback: feedbackCounts(feedback.map((entry) => entry.state)),
		practices: results.map((result): ReviewPractice => ({
			practiceSlug: result.practiceSlug,
			practiceName: result.practiceName,
			practiceVersionId: result.practiceVersionId,
			number: 1,
			status: "DONE",
			reason: null,
			script: null,
			itemCounts: { listed: 1, reviewed: 1, reused: 0, withheld: 0, notReviewed: 0 },
			results: resultCounts([result.outcome]),
		})),
	});
	if (review.runtime === null || review.runtime.jobs.length !== 1) {
		throw new globalThis.Error("The rich review must have one synthetic worker run");
	}
	const startedAt = new Date(review.createdAt.getTime() + 40_000);
	const runCompletedAt = new Date(completedAt.getTime() - 5000);
	review.runtime.jobs = review.runtime.jobs.map((job) => ({
		...job,
		availableAt: review.createdAt,
		startedAt,
		completedAt: runCompletedAt,
	}));
	review.stageTiming = [
		{
			stage: "RUN",
			startedAt,
			completedAt: runCompletedAt,
			durationMs: runCompletedAt.getTime() - startedAt.getTime(),
		},
	];
	if (review.startedBy !== null) {
		review.startedBy = {
			...review.startedBy,
			itemId: review.item.id,
			occurredAt: new Date(review.createdAt.getTime() - 20_000),
			decidedAt: review.createdAt,
		};
	}
	const lineage = storyLineage({
		root: { kind: "REVIEW", id: review.id },
		item: gitlabMergeRequest,
		reviews: [reviewRefOf(review)],
		results: results.map((result) => ({
			result: boundResultOf(result),
			problems: result.problems,
		})),
		feedback: feedback.map((entry) => ({
			feedback: boundFeedbackOf(entry),
			basis: [...new Set(entry.basis.map((source) => source.resultId))],
		})),
	});
	lineage.edges.push(
		...feedback.flatMap((entry) =>
			entry.basis.flatMap((basis) =>
				basis.problemId === null
					? []
					: [
							lineageEdge(
								lineageKey("PROBLEM", basis.problemId),
								lineageKey("FEEDBACK", entry.id),
								LINEAGE_RELATION.basis,
							),
						],
			),
		),
	);
	return {
		review,
		lineage,
		results,
		problems,
		feedback,
		resultsById: new Map(results.map((result) => [result.id, result])),
		problemsById: new Map(problems.map((problem) => [problem.id, problem])),
		feedbackById: new Map(feedback.map((entry) => [entry.id, entry])),
	};
})();

const resultCases: LineageStateCase[] = [
	withCase(
		"outcomes",
		"Every outcome, and not met by severity",
		chain({ results: outcomeGrid, feedback: [] }),
	),
	withCase(
		"stale",
		"Stale: the work changed after the review",
		chain({ results: threeResults({ currentness: "STALE" }) }),
	),
	withCase(
		"unverifiable",
		"Unverifiable: whether the work changed cannot be told",
		chain({ results: threeResults({ currentness: "UNVERIFIABLE" }) }),
	),
	withCase(
		"answered-earlier",
		"Two practices answered by an earlier review",
		chain({
			results: [
				...threeResults().slice(0, 1),
				...threeResults()
					.slice(1)
					.map((entry) => ({
						...entry,
						result: { ...entry.result, reviewId: EARLIER_REVIEW_ID, recordedAt: at("11:02:00") },
					})),
			],
		}),
	),
	withCase(
		"evidence-hidden",
		"Not met, its evidence withheld from admins",
		chain({
			results: [
				stateResult(RESULT_A, {}, [
					{ id: problemOf(RESULT_A, 0, "MAJOR").id, severity: "MAJOR", summary: "Evidence hidden" },
				]),
			],
		}),
	),
	withCase("not-met-silent", "Not met, and no feedback written from it", chain({ feedback: [] })),
	withCase("empty", "Complete with no observation", chain({ results: [], feedback: [] })),
	{
		key: "many-results",
		title: "24 observations across practices, with 12 problems and six pieces of feedback",
		lineage: richReviewFixture.lineage,
		review: richReviewFixture.review,
	},
];

const DESTINATIONS = ["ON_WORK", "PRACTICE_PAGE", "CONVERSATION"] as const;

const destinationCases: LineageStateCase[] = [
	withCase(
		"destinations",
		"Every destination",
		chain({
			feedback: DESTINATIONS.map((destination, index) =>
				stateFeedback(id(7, index + 1), [RESULT_A], {
					destination,
					deliveredAt: at(`13:4${index}:20`),
				}),
			),
		}),
	),
	...DESTINATIONS.map((destination) =>
		withCase(
			`destination-${destination.toLowerCase().replaceAll("_", "-")}`,
			`Only ${destination.toLowerCase().replaceAll("_", " ")}`,
			chain({ feedback: [oneComment({ destination })] }),
		),
	),
];

const STATES = [
	"AWAITING_APPROVAL",
	"READY",
	"DELIVERED",
	"UNCONFIRMED",
	"WITHHELD",
	"REJECTED",
	"REPLACED",
	"FAILED",
] as const satisfies readonly ReviewBoundFeedback["state"][];

const deliveryCases: LineageStateCase[] = [
	withCase(
		"delivery-states",
		"Every delivery state",
		chain({
			feedback: STATES.map((state, index) =>
				stateFeedback(id(8, index + 1), [RESULT_A], {
					state,
					destination: state === "UNCONFIRMED" ? "CONVERSATION" : "ON_WORK",
					deliveredAt: state === "DELIVERED" || state === "REPLACED" ? at(`13:4${index}:30`) : null,
				}),
			),
		}),
	),
	withCase(
		"withdrawn",
		"Shown on their practice pages, then withdrawn",
		chain({
			feedback: [oneComment({ destination: "PRACTICE_PAGE", withdrawn: true })],
		}),
	),
	withCase(
		"replaced",
		"An earlier comment, replaced by this one",
		chain({
			feedback: [
				oneComment(),
				stateFeedback(id(9, 1), [RESULT_A], {
					state: "REPLACED",
					deliveredAt: at("09:12:20"),
				}),
			],
		}),
	),
	...STATES.map((state) =>
		withCase(
			`state-${state.toLowerCase().replaceAll("_", "-")}`,
			`Only ${state.toLowerCase().replaceAll("_", " ")}`,
			chain({
				feedback: [
					oneComment({
						state,
						deliveredAt: state === "DELIVERED" || state === "REPLACED" ? at("13:41:30") : null,
					}),
				],
			}),
		),
	),
];

const volumeCases: LineageStateCase[] = [
	withCase(
		"many-feedback",
		"Ten pieces of feedback from three observations",
		chain({
			feedback: Array.from({ length: 10 }, (_, index) =>
				stateFeedback(
					id(10, index + 1),
					index % 3 === 0
						? [RESULT_A, RESULT_B]
						: [[RESULT_A, RESULT_B, RESULT_C][index % 3] ?? RESULT_A],
					{
						destination: index % 2 === 0 ? "ON_WORK" : "PRACTICE_PAGE",
						deliveredAt: at(`13:4${index}:40`),
					},
				),
			),
		}),
	),
];

export const LINEAGE_STATE_CASES: Record<LineageStateGroup, LineageStateCase[]> = {
	review: reviewCases,
	origin: originCases,
	item: itemCases,
	results: resultCases,
	destination: destinationCases,
	delivery: deliveryCases,
	volume: volumeCases,
};

export const LINEAGE_STATE_GROUP_TITLES: Record<LineageStateGroup, string> = {
	review: "How the review ran",
	origin: "What started it",
	item: "The work reviewed",
	results: "Observations",
	destination: "Where feedback went",
	delivery: "What became of feedback",
	volume: "Many records",
};

export function lineageStateCase(key: string): LineageStateCase {
	for (const cases of Object.values(LINEAGE_STATE_CASES)) {
		const found = cases.find((entry) => entry.key === key);
		if (found !== undefined) {
			return found;
		}
	}
	throw new Error(`No lineage state case ${key}`);
}

export const STATE_FEEDBACK_ID = FEEDBACK_A;
export const STATE_RESULT_ID = RESULT_A;
