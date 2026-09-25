/**
 * One practice group's practices and the reviews that reached one of them, as the detail levels
 * show them: the standings of the group the practice-profile fixtures open, one observation in
 * full, and three runs on the same practice. Beside them, one observation per warrant a review
 * writes when it records neither a strength nor a problem — a search, an inapplicability, an
 * undecidability — which the row shows under "What was checked".
 */
import type {
	EvidenceCitation,
	ObservationDetail,
	PracticeGroupReviewRun,
	PracticeStanding,
} from "@/api/types.gen";
import { daysBefore } from "@/stories/story-clock";

import { packagingGroup, practicesByGroup, pullRequest } from "./practice-profile-story-mock-data";

const packagingPractices = practicesByGroup[packagingGroup.slug] ?? [];

/**
 * What the detail level shows and the profile's list does not: each practice's prose, and the
 * trend of the one the runs below reviewed. The practices themselves come from
 * `practicesByGroup`, so the group has one practice list across the story fixtures.
 */
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
			calendarSpanDays: 12,
			bundleSize: 4,
			ropeHalfWidth: 0.15,
			credibilityThreshold: 0.9,
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

/** The practice the runs below reviewed, as the practice level opens it. */
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

/** The pull request the runs below reviewed, as an observation names it. */
const reviewedPullRequest = { artifactId: 902, artifactKind: "scm.pull_request" } as const;

/** An observation the current rules made as the work arrived — the ordinary case. */
const live = { origin: "LIVE", claimCurrentness: "CURRENT" } as const;

/**
 * Two quotes of the pull request itself, as the runner records them: offsets into its own
 * serialised context, which is why the row folds them into one block named for the source rather
 * than into two blocks pointing at `metadata.json`.
 */
const pullRequestItself: EvidenceCitation[] = [
	{
		sourceKind: "scm.pull-request.core",
		artifactPath: "HephaestusTest/practice-validation#902",
		path: "inputs/context/metadata.json",
		startLine: 4,
		endLine: 4,
		quote: '"title" : "Page size"',
		quoteRedacted: false,
	},
	{
		sourceKind: "scm.pull-request.core",
		artifactPath: "HephaestusTest/practice-validation#902",
		path: "inputs/context/metadata.json",
		startLine: 9,
		endLine: 9,
		quote: '"body" : ""',
		quoteRedacted: false,
	},
];

export const detailObservation: ObservationDetail = {
	id: "00000000-0000-0000-0000-000000000102",
	feedbackResponse: { feedbackId: "00000000-0000-0000-0000-000000000103" },
	practiceSlug: focusedChanges.slug,
	practiceName: focusedChanges.name,
	summary: "The refactor and the fix arrived together",
	assessmentStatus: "ASSESSED",
	presence: "PRESENT",
	assessment: "BAD",
	severity: "MAJOR",
	observedAt: daysBefore(2),
	...live,
	...reviewedPullRequest,
	artifactUrl: "https://github.com/HephaestusTest/practice-validation/pull/902",
	evidenceRationale:
		"The diff renames the loader's package and changes its caching in the same commit, so a reviewer cannot tell which hunk carries the behaviour change.",
	deliveredFeedback:
		"Land the rename on its own first; the caching change then reads as the small diff it is.",
	evidence: {
		detector: "practice-observer",
		citations: [
			{
				sourceKind: "scm.pull-request.diff",
				artifactPath: "HephaestusTest/practice-validation#902",
				path: "server/application/src/main/java/de/tum/cit/aet/hephaestus/practices/catalog/PracticeCatalogLoader.java",
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
				quoteRedacted: false,
			},
			{
				sourceKind: "scm.pull-request.diff",
				artifactPath: "HephaestusTest/practice-validation#902",
				path: "server/application/src/main/java/de/tum/cit/aet/hephaestus/practices/PracticeLoader.java",
				side: "OLD",
				startLine: 1,
				endLine: 1,
				quoteRedacted: false,
			},
			...pullRequestItself,
		],
	},
};

export const detailRun: PracticeGroupReviewRun = {
	reviewId: "00000000-0000-0000-0000-000000000101",
	reviewedAt: daysBefore(2),
	reviewedWork: pullRequest(902),
	lead: "The change is sound, but it carries a package move and a caching change in one diff.",
	practicesEvaluated: 19,
	practicesEligible: 19,
	durationSeconds: 528,
	observations: [
		detailObservation,
		{
			id: "00000000-0000-0000-0000-000000000104",
			practiceSlug: "describe-what-and-why",
			practiceName: "Describe what changed and why",
			summary: "The description names the motivation",
			assessmentStatus: "ASSESSED",
			presence: "PRESENT",
			assessment: "GOOD",
			observedAt: daysBefore(2),
			...live,
			...reviewedPullRequest,
			evidenceRationale:
				"The description opens with why the loader moved, before it says what moved.",
		},
	],
};

/** Three reviews that reached the practice, newest first, as the level's timeline shows them. */
export const detailRuns: PracticeGroupReviewRun[] = [
	detailRun,
	{
		reviewId: "00000000-0000-0000-0000-000000000111",
		reviewedAt: daysBefore(4),
		reviewedWork: pullRequest(898),
		observations: [
			{
				id: "00000000-0000-0000-0000-000000000112",
				feedbackResponse: { feedbackId: "00000000-0000-0000-0000-000000000113" },
				practiceSlug: focusedChanges.slug,
				practiceName: focusedChanges.name,
				summary: "A dependency bump was carried alongside a behaviour change",
				assessmentStatus: "ASSESSED",
				presence: "PRESENT",
				assessment: "BAD",
				severity: "MINOR",
				observedAt: daysBefore(4),
				...live,
				artifactId: 898,
				artifactKind: "scm.pull_request",
				evidenceRationale:
					"The lockfile change and the retry loop landed in one commit, so the bump cannot be reverted alone.",
				deliveredFeedback: "Bump dependencies in a pull request of their own.",
			},
		],
	},
	{
		reviewId: "00000000-0000-0000-0000-000000000121",
		reviewedAt: daysBefore(11),
		reviewedWork: pullRequest(884),
		observations: [
			{
				id: "00000000-0000-0000-0000-000000000122",
				feedbackResponse: { feedbackId: "00000000-0000-0000-0000-000000000123" },
				practiceSlug: focusedChanges.slug,
				practiceName: focusedChanges.name,
				summary: "One concern, one pull request: the export and nothing else",
				assessmentStatus: "ASSESSED",
				presence: "PRESENT",
				assessment: "GOOD",
				observedAt: daysBefore(11),
				...live,
				artifactId: 884,
				artifactKind: "scm.pull_request",
				evidenceRationale: "Every hunk serves the export; nothing else moved.",
			},
		],
	},
];

/** What every warrant fixture below shares: the practice level's own practice, on the same work. */
const onThatPullRequest = {
	practiceSlug: focusedChanges.slug,
	practiceName: focusedChanges.name,
	observedAt: daysBefore(2),
	...live,
	...reviewedPullRequest,
	artifactUrl: "https://github.com/HephaestusTest/practice-validation/pull/902",
} satisfies Partial<ObservationDetail>;

/** Nothing found where the review looked, with the reach of that absence written down. */
export const searchedAndFoundNothing: ObservationDetail = {
	...onThatPullRequest,
	id: "00000000-0000-0000-0000-000000000131",
	summary: "No test covers the new caching branch",
	assessmentStatus: "ASSESSED",
	presence: "ABSENT",
	assessment: "GOOD",
	severity: "MAJOR",
	evidenceRationale:
		"The branch is new in this change: `loadFromCache` is called in `DocumentLoader`, and no test file in the diff names it at all.",
	evidence: {
		detector: "practice-observer",
		citations: [],
		search: {
			lookedFor: "a test exercising the new caching branch of the loader",
			consulted: ["scm.pull-request.diff", "scm.repository.tree"],
			boundary:
				"every test file the diff touches and the repository's own test tree; I did not read test sources outside it",
		},
	},
};

/** The practice had nothing to judge here, with the fact about the work that settled it. */
export const nothingToJudge: ObservationDetail = {
	...onThatPullRequest,
	id: "00000000-0000-0000-0000-000000000141",
	summary: "This change performs no network request",
	assessmentStatus: "NOT_APPLICABLE",
	evidence: {
		detector: "practice-observer",
		citations: [],
		inapplicability: {
			subject: "how a change handles a network call that times out",
			consulted: ["scm.pull-request.diff"],
			ruledOutBy: "nothing in the diff calls out of the process",
		},
	},
};

/** The review could not settle the question, and says what would have. */
export const couldNotSettleIt: ObservationDetail = {
	...onThatPullRequest,
	id: "00000000-0000-0000-0000-000000000151",
	summary: "The evidence does not settle whether the rename was asked for",
	assessmentStatus: "UNDETERMINED",
	evidenceRationale:
		"The description mentions a review thread the review was not given, so the two changes may have been requested together.",
	evidence: {
		detector: "practice-observer",
		citations: [],
		undecidability: {
			openQuestion: "whether a reviewer asked for the package move in this same request",
			wouldSettleIt: "the review thread the description points at",
		},
	},
};

/**
 * A next step written about this work while nothing was delivered: the row shows the reviewer's
 * own sentence, which is the one the reader can act on.
 */
export const nextStepWithoutDelivery: ObservationDetail = {
	...onThatPullRequest,
	id: "00000000-0000-0000-0000-000000000161",
	summary: "The rename and the caching change share one commit",
	assessmentStatus: "ASSESSED",
	presence: "PRESENT",
	assessment: "BAD",
	severity: "MINOR",
	nextStep: "Split the commit so the rename can be reverted without the caching change.",
};
