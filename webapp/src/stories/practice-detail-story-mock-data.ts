import type {
	EvidenceCitation,
	ItemRef,
	OwnFeedbackRef,
	PracticeStanding,
	Problem,
	ProfileReview,
	ResultDetail,
	ResultList,
	ReviewRef,
} from "@/api/types.gen";
import { ITEM_KIND } from "@/lib/item-kinds";
import { daysBefore } from "@/stories/story-clock";

import { packagingGroup, practicesByGroup, pullRequest } from "./practice-profile-story-mock-data";

const packagingPractices = practicesByGroup[packagingGroup.slug] ?? [];

const detailDepth: Record<string, Partial<PracticeStanding>> = {
	"scope-one-reviewable-change": {
		whyItMatters:
			"A change that does one thing is faster to understand, safer to revert and easier to review well.",
		whatGoodLooksLike:
			"One concern per pull request; a refactor lands before the behaviour change that needed it.",
		direction: "IMPROVING",
		trendSupport: {
			currentOpportunities: 6,
			previousOpportunities: 5,
			opportunities: 11,
			opportunitiesUntilComparable: 0,
			standingWork: 4,
			reviewedWork: 11,
			reviewedWorkKind: ITEM_KIND.pullRequest,
			calendarSpanDays: 12,
			bundleSize: 4,
			ropeHalfWidth: 0.15,
			credibilityThreshold: 0.9,
			comparablePractices: null,
			eligiblePractices: null,
			firstOpportunityAt: daysBefore(30),
			lastOpportunityAt: daysBefore(2),
		},
	},
	"reviewable-diff-size": {
		whyItMatters: "A diff a reviewer can hold in their head is the one they read line by line.",
		whatGoodLooksLike: "A few hundred lines of real change, with the mechanical parts split out.",
	},
	"describe-what-and-why": {
		whyItMatters: "Reviewers need intent to judge whether the change solves the right problem.",
		whatGoodLooksLike: "A concise summary, the motivation, and how it was verified.",
	},
	"ready-and-traceable-handoff": {
		whyItMatters: "The issue carries the discussion a reviewer would otherwise have to ask for.",
	},
};

export const detailPractices: PracticeStanding[] = packagingPractices.map((practice) => ({
	...practice,
	...detailDepth[practice.slug],
}));

export const focusedChanges: PracticeStanding = detailPractice("scope-one-reviewable-change");

/** The group's practice no feedback card names: a level with nothing to list. */
export const unwrittenAbout: PracticeStanding = detailPractice(
	"commit-subjects-explain-each-change",
);

function detailPractice(slug: string): PracticeStanding {
	const practice = detailPractices.find((candidate) => candidate.slug === slug);
	if (practice === undefined) {
		throw new Error(`${packagingGroup.slug} has no practice ${slug}.`);
	}
	return practice;
}

const pullRequest902: ItemRef = {
	...pullRequest(902),
	title: "Move the practice loader into the catalogue package",
};

export function reviewRef(
	id: string,
	item: ItemRef,
	createdAt: Date,
	fields: Partial<ReviewRef> = {},
): ReviewRef {
	return {
		id,
		kind: "ITEM",
		item,
		itemVersion: null,
		origin: "EVENT",
		status: "COMPLETE",
		createdAt,
		completedAt: createdAt,
		...fields,
	};
}

function cited(
	fields: Partial<EvidenceCitation> & Pick<EvidenceCitation, "sourceKind">,
): EvidenceCitation {
	return {
		sourcePath: null,
		path: null,
		side: null,
		commit: null,
		startLine: null,
		endLine: null,
		quote: null,
		verification: null,
		...fields,
	};
}

function problem(
	fields: Pick<Problem, "id" | "resultId" | "position" | "severity" | "summary"> & Partial<Problem>,
): Problem {
	return { key: null, explanation: null, citations: [], basis: null, nextStep: null, ...fields };
}

export function ownFeedback(id: string, fields: Partial<OwnFeedbackRef> = {}): OwnFeedbackRef {
	return {
		id,
		destination: "ON_WORK",
		state: "DELIVERED",
		deliveredAt: daysBefore(2),
		response: null,
		copies: [],
		...fields,
	};
}

export function listedResult(result: ResultDetail): ResultList {
	return {
		id: result.id,
		reviewId: result.reviewId,
		item: result.item,
		practiceSlug: result.practiceSlug,
		practiceName: result.practiceName,
		outcome: result.outcome,
		severity: result.severity,
		summary: result.summary,
		origin: result.origin,
		recordedAt: result.recordedAt,
		currentness: result.currentness,
		markedIncorrectAt: result.markedIncorrectAt,
		acceptedAt: result.acceptedAt,
		problems: result.problems.map(({ id, severity, summary }) => ({ id, severity, summary })),
	};
}

function recorded(
	fields: Pick<ResultDetail, "id" | "reviewId" | "item" | "outcome" | "recordedAt"> &
		Partial<ResultDetail>,
): ResultDetail {
	return {
		practiceSlug: focusedChanges.slug,
		practiceName: focusedChanges.name,
		severity: null,
		summary: null,
		origin: "EVENT",
		currentness: "CURRENT",
		markedIncorrectAt: null,
		acceptedAt: null,
		practiceVersionId: "41",
		practiceVersionNumber: 3,
		itemVersion: null,
		explanation: null,
		evidence: null,
		problems: [],
		example: null,
		coverage: null,
		endedAt: null,
		endReason: null,
		feedback: [],
		...fields,
	};
}

const PULL_REQUEST_902 = "HephaestusTest/practice-validation#902";
const LOADER_PATH =
	"server/application/src/main/java/de/tum/cit/aet/hephaestus/practices/catalog/PracticeCatalogLoader.java";

/**
 * Two quotes of the pull request itself, as the runner records them: offsets into its own
 * serialised context, which is why the row folds them into one block named for the source rather
 * than into two blocks pointing at `metadata.json`.
 */
const pullRequestItself: EvidenceCitation[] = [
	cited({
		sourceKind: "scm.pull-request.core",
		sourcePath: PULL_REQUEST_902,
		path: "inputs/context/metadata.json",
		startLine: 4,
		endLine: 4,
		quote: `"title" : ${JSON.stringify(pullRequest902.title)}`,
	}),
	cited({
		sourceKind: "scm.pull-request.core",
		sourcePath: PULL_REQUEST_902,
		path: "inputs/context/metadata.json",
		startLine: 9,
		endLine: 9,
		quote:
			'"body" : "Move the loader into the catalogue package so catalogue code lives together."',
	}),
];

const DETAIL_REVIEW = "00000000-0000-0000-0000-000000000101";
const DETAIL_RESULT = "00000000-0000-4000-8000-000000000102";

export const detailFeedback: OwnFeedbackRef = ownFeedback("00000000-0000-0000-0000-000000000103", {
	copies: [
		{
			id: "00000000-0000-0000-0000-0000000001c1",
			kind: "SUMMARY",
			externalUrl: `${pullRequest902.url ?? ""}#issuecomment-2481902`,
			postedAt: daysBefore(2),
		},
		{
			id: "00000000-0000-0000-0000-0000000001c2",
			kind: "LINE_NOTE",
			externalUrl: `${pullRequest902.url ?? ""}#discussion_r2481903`,
			postedAt: daysBefore(2),
		},
	],
});

export const detailResult: ResultDetail = recorded({
	id: DETAIL_RESULT,
	reviewId: DETAIL_REVIEW,
	item: pullRequest902,
	summary: "The refactor and the fix arrived together",
	outcome: "NOT_MET",
	severity: "MAJOR",
	recordedAt: daysBefore(2),
	explanation:
		"The diff renames the loader's package and changes its caching in the same commit, so a reviewer cannot tell which hunk carries the behaviour change.",
	problems: [
		problem({
			id: "00000000-0000-0000-0000-0000000001b1",
			resultId: DETAIL_RESULT,
			position: 0,
			severity: "MAJOR",
			summary: "The caching change hides inside the package move",
			explanation:
				"`PracticeCatalogLoader` gains `@Cacheable` in the same hunk that moves it, so the diff shows the behaviour change as a moved file.",
			nextStep: "Move the class in one commit and add the cache in the next.",
			citations: [
				cited({
					sourceKind: "scm.pull-request.diff",
					sourcePath: PULL_REQUEST_902,
					path: LOADER_PATH,
					side: "NEW",
					startLine: 41,
					endLine: 46,
					quote:
						'@Cacheable(cacheNames = "practice-catalog", key = "#workspaceId")\n' +
						"public PracticeCatalog load(long workspaceId) {\n" +
						"    var practices = repository.findAllByWorkspaceId(workspaceId);\n" +
						"    return PracticeCatalog.of(practices);\n" +
						"}\n" +
						"// moved from de.tum.cit.aet.hephaestus.practices.PracticeLoader",
				}),
			],
		}),
		problem({
			id: "00000000-0000-0000-0000-0000000001b2",
			resultId: DETAIL_RESULT,
			position: 1,
			severity: "MINOR",
			summary: "The old loader is deleted rather than moved",
			explanation:
				"Git records a delete and an add, so the history of the old file stops at this commit.",
			citations: [
				cited({
					sourceKind: "scm.pull-request.diff",
					sourcePath: PULL_REQUEST_902,
					path: "server/application/src/main/java/de/tum/cit/aet/hephaestus/practices/PracticeLoader.java",
					side: "OLD",
					startLine: 1,
					endLine: 1,
				}),
			],
		}),
		problem({
			id: "00000000-0000-0000-0000-0000000001b3",
			resultId: DETAIL_RESULT,
			position: 2,
			severity: "MINOR",
			summary: "The description names only the move",
			explanation: "A reviewer reading the description would not look for a behaviour change.",
			nextStep: "Say in the description that the loader now caches per workspace.",
		}),
	],
	evidence: {
		citations: pullRequestItself,
		openQuestions: [],
		search: null,
		inapplicability: null,
	},
	feedback: [detailFeedback],
});

export const metWithoutSummary: ResultDetail = recorded({
	id: "00000000-0000-0000-0000-000000000104",
	reviewId: DETAIL_REVIEW,
	item: pullRequest902,
	practiceSlug: "describe-what-and-why",
	practiceName: "Describe what changed and why",
	outcome: "MET",
	recordedAt: daysBefore(2),
	explanation: "The description opens with why the loader moved, before it says what moved.",
});

export const detailReview: ProfileReview = {
	review: reviewRef(DETAIL_REVIEW, pullRequest902, daysBefore(2)),
	positionOnWork: 1,
	totalOnWork: 1,
	results: [listedResult(detailResult), listedResult(metWithoutSummary)],
	feedback: [detailFeedback],
};

/** Three reviews that reached the practice, newest first, as the level's timeline shows them. */
export const detailReviews: ProfileReview[] = [
	detailReview,
	{
		review: reviewRef("00000000-0000-0000-0000-000000000111", pullRequest(898), daysBefore(4)),
		positionOnWork: 1,
		totalOnWork: 1,
		results: [
			listedResult(
				recorded({
					id: "00000000-0000-0000-0000-000000000112",
					reviewId: "00000000-0000-0000-0000-000000000111",
					item: pullRequest(898),
					summary: "A dependency bump was carried alongside a behaviour change",
					outcome: "NOT_MET",
					severity: "MINOR",
					recordedAt: daysBefore(4),
				}),
			),
		],
		feedback: [
			ownFeedback("00000000-0000-0000-0000-000000000113", {
				deliveredAt: daysBefore(4),
				response: {
					resolution: "ADDRESSED",
					usefulness: "HELPFUL",
					comment: "Moved the bump to its own pull request.",
					createdAt: daysBefore(3),
				},
			}),
		],
	},
	{
		review: reviewRef("00000000-0000-0000-0000-000000000121", pullRequest(884), daysBefore(11)),
		positionOnWork: 1,
		totalOnWork: 1,
		results: [
			listedResult(
				recorded({
					id: "00000000-0000-0000-0000-000000000122",
					reviewId: "00000000-0000-0000-0000-000000000121",
					item: pullRequest(884),
					summary: "One concern, one pull request: the export and nothing else",
					outcome: "MET",
					recordedAt: daysBefore(11),
				}),
			),
		],
		feedback: [],
	},
];

/**
 * A review the reader asked for, newer than the three above, that found no problem on the practice
 * the reviews started by events still read as needing attention.
 */
export const requestedCleanReview: ProfileReview = {
	review: reviewRef("00000000-0000-0000-0000-000000000141", pullRequest902, daysBefore(1), {
		origin: "REQUEST",
	}),
	positionOnWork: 1,
	totalOnWork: 1,
	results: [
		listedResult(
			recorded({
				id: "00000000-0000-0000-0000-000000000142",
				reviewId: "00000000-0000-0000-0000-000000000141",
				item: pullRequest902,
				summary: "The rename and the caching change are now separate commits",
				outcome: "MET",
				origin: "REQUEST",
				recordedAt: daysBefore(1),
			}),
		),
	],
	feedback: [],
};

/** What every warrant fixture below shares: the practice level's own practice, on the same work. */
const onThatPullRequest = {
	reviewId: DETAIL_REVIEW,
	item: pullRequest902,
	recordedAt: daysBefore(2),
} satisfies Partial<ResultDetail>;

/** Nothing found where the review looked, with the reach of that absence written down. */
export const searchedAndFoundNothing: ResultDetail = recorded({
	...onThatPullRequest,
	id: "00000000-0000-0000-0000-000000000131",
	summary: "No test covers the new caching branch",
	outcome: "NOT_MET",
	severity: "MAJOR",
	explanation:
		"The branch is new in this change: `loadFromCache` is called in `DocumentLoader`, and no test file in the diff names it at all.",
	problems: [
		problem({
			id: "00000000-0000-0000-0000-000000000132",
			resultId: "00000000-0000-0000-0000-000000000131",
			position: 0,
			severity: "MAJOR",
			summary: "The cache hit path has no test",
			explanation: "Every test in the diff starts from an empty cache.",
		}),
	],
	evidence: {
		citations: [],
		openQuestions: [],
		inapplicability: null,
		search: {
			lookedFor: "a test exercising the new caching branch of the loader",
			consulted: ["scm.pull-request.diff", "scm.repository.tree"],
			boundary:
				"every test file the diff touches and the repository's own test tree; I did not read test sources outside it",
		},
	},
});

export const undeterminedResult: ResultDetail = recorded({
	...onThatPullRequest,
	id: "00000000-0000-0000-0000-000000000171",
	summary: "Whether the rename was asked for is not on the record",
	outcome: "COULD_NOT_DETERMINE",
	explanation:
		"The description links an issue the review could not read, and the diff alone does not say whether the move was requested.",
	evidence: {
		citations: [],
		search: null,
		inapplicability: null,
		openQuestions: [
			{
				openQuestion: "Was the package move asked for in the linked issue?",
				wouldSettleIt: "The text of issue #880, which the description links.",
				citation: cited({
					sourceKind: "scm.pull-request.core",
					sourcePath: PULL_REQUEST_902,
					path: "inputs/context/metadata.json",
					startLine: 9,
					endLine: 9,
					quote: '"body" : "Closes #880"',
				}),
			},
			{
				openQuestion: "Does anything outside this repository import the old package?",
				wouldSettleIt: "A search of the other services that depend on the catalog.",
				citation: null,
			},
		],
	},
});

export const metWithExample: ResultDetail = recorded({
	...onThatPullRequest,
	id: "00000000-0000-0000-0000-000000000181",
	summary: "Each commit carries one concern",
	outcome: "MET",
	explanation: "The rename and the caching change land in separate commits.",
	example: {
		note: "This commit moves the loader and changes nothing else, so it can be reverted on its own.",
		citation: cited({
			sourceKind: "scm.pull-request.diff",
			sourcePath: PULL_REQUEST_902,
			path: LOADER_PATH,
			side: "NEW",
			startLine: 1,
			endLine: 1,
			quote: "package de.tum.cit.aet.hephaestus.practices.catalog;",
		}),
	},
	evidence: { citations: [], openQuestions: [], search: null, inapplicability: null },
});
