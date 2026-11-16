import type {
	GroupRef,
	ItemRef,
	PracticeGroupStanding,
	PracticeGuidanceDto,
	PracticeProfileOverview,
	PracticesAcrossWorkspace,
	PracticesAcrossWorkspaceTiles,
	PracticeStanding,
	ProfileChange,
	ProfileReview,
	ProfileReviewRef,
	ResultList,
	TrendSupport,
	WorkspaceGroupSplit,
	WorkspacePracticeSplit,
	WorkspaceSplit,
} from "@/api/types.gen";
import { text } from "@/components/practice-vocabulary/feedback-text";
import { getGroupVisual } from "@/components/practice-vocabulary/group-visuals";
import type { PracticeFeedbackCardEntry } from "@/components/practice-vocabulary/PracticeFeedbackCard";
import { ITEM_KIND, type ItemProvider } from "@/lib/item-kinds";

/**
 * The made-up Practice profile that the tour opens its levels over: a developer after a few weeks of
 * reviews, in a made-up workspace. It belongs to the tour, not to the stories: a change to a test
 * fixture must not change what the product teaches. The tour shows it only under the label
 * "Example, not your work", and never mixes it into the reader's own page.
 */

const DAY_MS = 86_400_000;

/** How the provider names a piece of work: #17 on GitHub, !17 on GitLab. */
export function exampleWorkLabel(number: number, provider: ItemProvider | undefined): string {
	return `${provider === "GITLAB" ? "!" : "#"}${number}`;
}

/** A made-up pull request or merge request, with no address: nothing in the example opens. */
export function exampleWork(number: number, provider: ItemProvider | undefined): ItemRef {
	return {
		id: `tour-example-${number}`,
		kind: ITEM_KIND.pullRequest,
		provider: provider ?? "GITHUB",
		label: exampleWorkLabel(number, provider),
		container: "example/web-shop",
	};
}

const PACKAGING: GroupRef = {
	slug: "tour-example-packaging",
	name: "Packaging work for review",
	icon: "Package",
	color: "sky",
};
const ACTING: GroupRef = {
	slug: "tour-example-acting",
	name: "Acting on review feedback",
	icon: "MessageSquareReply",
	color: "cyan",
};
const TESTING: GroupRef = {
	slug: "tour-example-testing",
	name: "Testing your changes",
	icon: "TestTube",
	color: "amber",
};

export const EXAMPLE_PROFILE_GROUPS = [PACKAGING, ACTING, TESTING];

type Standing = PracticeStanding["standing"];

interface PracticeSeed {
	slug: string;
	name: string;
	standing: Standing;
	whyItMatters?: string;
	whatGoodLooksLike?: string;
}

const SEEDS: Record<string, PracticeSeed[]> = {
	[PACKAGING.slug]: [
		{
			slug: "tour-example-describe-why",
			name: "Describe what changed and why",
			standing: "DEVELOPING",
			whyItMatters:
				"A reviewer who knows why a change is needed can judge whether it solves the problem.",
			whatGoodLooksLike:
				"The description says the problem first, then what changed and how to check it.",
		},
		{
			slug: "tour-example-one-concern",
			name: "Scope the change to one concern",
			standing: "MIXED",
			whyItMatters: "A change that does one thing is faster to understand and safer to revert.",
		},
		{
			slug: "tour-example-diff-size",
			name: "Keep the diff reviewable in one sitting",
			standing: "STRENGTH",
		},
		{
			slug: "tour-example-commit-subjects",
			name: "Write commit subjects a reviewer can follow",
			standing: "STRENGTH",
		},
	],
	[ACTING.slug]: [
		{ slug: "tour-example-respond", name: "Respond to each review comment", standing: "MIXED" },
		{
			slug: "tour-example-resolve-threads",
			name: "Resolve open threads before merging",
			standing: "STRENGTH",
		},
	],
	[TESTING.slug]: [
		{ slug: "tour-example-tests", name: "Include tests with the change", standing: "NOT_OBSERVED" },
	],
};

/** The practice whose level the tour opens: the one the example's open card is about. */
const FOCUS_SLUG = "tour-example-describe-why";

function settled(standing: Standing): boolean {
	return standing === "DEVELOPING" || standing === "MIXED" || standing === "STRENGTH";
}

function support(now: Date): TrendSupport {
	return {
		currentOpportunities: 4,
		previousOpportunities: 4,
		opportunities: 8,
		opportunitiesUntilComparable: 0,
		standingWork: 4,
		reviewedWork: 9,
		reviewedWorkKind: ITEM_KIND.pullRequest,
		bundleSize: 4,
		ropeHalfWidth: 0.15,
		credibilityThreshold: 0.9,
		calendarSpanDays: 21,
		comparablePractices: null,
		eligiblePractices: null,
		firstOpportunityAt: new Date(now.getTime() - 24 * DAY_MS),
		lastOpportunityAt: new Date(now.getTime() - DAY_MS),
	};
}

function practiceStanding(group: GroupRef, seed: PracticeSeed, now: Date): PracticeStanding {
	const focus = seed.slug === FOCUS_SLUG;
	return {
		slug: seed.slug,
		name: seed.name,
		groupSlug: group.slug,
		groupName: group.name,
		standing: seed.standing,
		direction: focus ? "DECLINING" : null,
		trendSupport: focus ? support(now) : null,
		doesNotApplyOnly: false,
		whyItMatters: seed.whyItMatters ?? null,
		whatGoodLooksLike: seed.whatGoodLooksLike ?? null,
		strengths: [],
		toWorkOn: [],
		basis: settled(seed.standing) ? "CURRENT_REVIEWS" : null,
	};
}

function groupStanding(
	group: GroupRef,
	standing: PracticeGroupStanding["standing"],
	direction: PracticeGroupStanding["direction"],
	now: Date,
): PracticeGroupStanding {
	const observed = standing !== "NOT_OBSERVED";
	return {
		groupSlug: group.slug,
		groupName: group.name,
		groupDescription: null,
		groupIcon: group.icon,
		groupColor: group.color,
		standing,
		direction,
		trendSupport: direction === null ? null : support(now),
		results: [],
		sources: observed ? [{ itemKind: ITEM_KIND.pullRequest, count: 9 }] : [],
		feedbackSince: observed ? new Date(now.getTime() - 24 * DAY_MS) : null,
		feedbackSpanDays: observed ? 24 : null,
		guidance: null,
		guidanceSource: null,
		basis: settled(standing) ? "CURRENT_REVIEWS" : null,
	};
}

/** Developers at Needs attention, Mixed feedback and Going well, then those with none yet. */
function split(needs: number, mixed: number, well: number, noneYet: number): WorkspaceSplit {
	return {
		parts: [
			{ standing: "DEVELOPING", developers: needs },
			{ standing: "MIXED", developers: mixed },
			{ standing: "STRENGTH", developers: well },
		],
		noneYet,
		developers: needs + mixed + well + noneYet,
	};
}

/**
 * One group across the workspace. Counts only: no `yourStanding`, since a You mark on made-up data
 * would state a standing for the reader.
 */
function acrossGroup(
	group: GroupRef,
	groupSplit: WorkspaceSplit,
	practices: [string, WorkspaceSplit][],
): WorkspaceGroupSplit {
	return {
		groupSlug: group.slug,
		groupName: group.name,
		groupIcon: group.icon,
		groupColor: group.color,
		yourStanding: null,
		yourBasis: null,
		split: groupSplit,
		practices: practices.map(([slug, practiceSplit]): WorkspacePracticeSplit => ({
			practiceSlug: slug,
			practiceName:
				Object.values(SEEDS)
					.flat()
					.find((seed) => seed.slug === slug)?.name ?? slug,
			yourStanding: null,
			yourBasis: null,
			split: practiceSplit,
		})),
	};
}

export interface ExampleProfile {
	groups: GroupRef[];
	practices: PracticeStanding[];
	practicesByGroup: Record<string, PracticeStanding[]>;
	groupStandings: Record<string, PracticeGroupStanding>;
	latestReview: ProfileReviewRef;
	overview: PracticeProfileOverview;
	feedbackCards: PracticeFeedbackCardEntry[];
	/** The group whose level the tour opens, and the practice inside it. */
	group: GroupRef;
	practice: PracticeStanding;
	guidance: PracticeGuidanceDto;
	practiceReviews: ProfileReview[];
	reviews: ProfileReview[];
	across: {
		overview: PracticesAcrossWorkspace;
		tiles: PracticesAcrossWorkspaceTiles;
		group: WorkspaceGroupSplit;
	};
}

/**
 * The whole example, with its dates a few days before `now` so it reads as recent. It takes the time
 * and the provider from its caller, so it reads no clock during render and names the work as the
 * reader's own forge does.
 */
export function exampleProfile(now: Date, provider?: ItemProvider): ExampleProfile {
	const daysAgo = (days: number) => new Date(now.getTime() - days * DAY_MS);
	const work = (number: number) => exampleWork(number, provider);
	const label = (number: number) => exampleWorkLabel(number, provider);

	const practicesByGroup = Object.fromEntries(
		EXAMPLE_PROFILE_GROUPS.map((group) => [
			group.slug,
			(SEEDS[group.slug] ?? []).map((seed) => practiceStanding(group, seed, now)),
		]),
	);
	const practices = Object.values(practicesByGroup).flat();
	const practice = practices.find((each) => each.slug === FOCUS_SLUG) ?? practices[0];
	if (practice === undefined) {
		throw new Error("The example has a practice to open");
	}

	const latestReview: ProfileReviewRef = {
		reviewId: "tour-example-review-21",
		at: daysAgo(1),
		item: work(21),
		status: "COMPLETE",
		progress: null,
	};

	const change = (
		type: ProfileChange["type"],
		group: GroupRef,
		seed: string | undefined,
		rest: Partial<ProfileChange>,
	): ProfileChange => ({
		type,
		at: daysAgo(1),
		groupSlug: group.slug,
		groupName: group.name,
		practiceSlug: seed,
		practiceName: practices.find((each) => each.slug === seed)?.name,
		evidence: [],
		...rest,
	});

	const overview: PracticeProfileOverview = {
		latestReview,
		activeReview: null,
		reviewReadiness: null,
		reviewedItems: [work(21)],
		window: { since: daysAgo(8), until: daysAgo(1) },
		changes: [
			change("FEEDBACK_NEW", PACKAGING, FOCUS_SLUG, {
				feedbackId: "tour-example-card",
				evidence: [work(17), work(19)],
			}),
			change("STANDING_MOVED", PACKAGING, "tour-example-diff-size", {
				from: "MIXED",
				to: "STRENGTH",
				direction: "UP",
				evidence: [work(19), work(21)],
			}),
			change("TREND_TURNED", ACTING, "tour-example-respond", {
				from: "UNCERTAIN",
				to: "IMPROVING",
				evidence: [work(21)],
			}),
		],
	};

	const { Icon: packagingIcon } = getGroupVisual(PACKAGING.icon, PACKAGING.color);
	const openCard: PracticeFeedbackCardEntry = {
		feedbackId: "tour-example-card",
		practiceSlug: FOCUS_SLUG,
		practiceName: practice.name,
		group: { slug: PACKAGING.slug, name: PACKAGING.name, color: "sky", icon: packagingIcon },
		headline: "Descriptions say what changed, not why",
		body: `The description of ${label(17)} says only what was added, and ${label(19)} has no description. A reviewer has to guess which problem each change solves.`,
		reviewedWork: [
			{ ref: work(17), date: daysAgo(9), outcome: "NOT_MET" },
			{ ref: work(19), date: daysAgo(4), outcome: "NOT_MET" },
		],
		cleanWork: [{ ref: work(21), date: daysAgo(1) }],
		cleanNeeded: 3,
		nextStep:
			"Start your next description with one sentence on the problem that the change solves.",
		condition: [text("Resolves when three pieces of work in a row come back clean.")],
		state: "new",
		timestamp: daysAgo(3),
		preparedAt: daysAgo(3),
	};
	const resolvedCard: PracticeFeedbackCardEntry = {
		feedbackId: "tour-example-resolved",
		practiceSlug: "tour-example-diff-size",
		practiceName: "Keep the diff reviewable in one sitting",
		group: openCard.group,
		headline: "Two pull requests grew past what one review can hold",
		body: `${label(12)} and ${label(14)} each touched more than thirty files, and both needed a second round of review.`,
		reviewedWork: [
			{ ref: work(12), date: daysAgo(20), outcome: "NOT_MET" },
			{ ref: work(14), date: daysAgo(16), outcome: "NOT_MET" },
		],
		cleanWork: [
			{ ref: work(17), date: daysAgo(9) },
			{ ref: work(19), date: daysAgo(4) },
			{ ref: work(21), date: daysAgo(1) },
		],
		cleanNeeded: 3,
		nextStep: "When a branch passes twenty files, split it and land the groundwork first.",
		condition: [text("Resolves when three pieces of work in a row come back clean.")],
		state: "resolved",
		timestamp: daysAgo(1),
		preparedAt: daysAgo(16),
	};

	const result = (
		id: string,
		number: number,
		days: number,
		seed: string,
		outcome: ResultList["outcome"],
		summary: string | null,
	): ResultList => ({
		id: `tour-example-result-${id}`,
		reviewId: `tour-example-review-${number}`,
		item: work(number),
		practiceSlug: seed,
		practiceName: practices.find((each) => each.slug === seed)?.name ?? seed,
		outcome,
		severity: outcome === "NOT_MET" ? "MINOR" : undefined,
		summary: summary ?? undefined,
		origin: "EVENT",
		recordedAt: daysAgo(days),
		currentness: "CURRENT",
		problems:
			outcome === "NOT_MET" && summary !== null
				? [{ id: `tour-example-problem-${id}`, severity: "MINOR", summary }]
				: [],
	});
	const review = (number: number, days: number, results: ResultList[]): ProfileReview => ({
		review: {
			id: `tour-example-review-${number}`,
			kind: "ITEM",
			item: work(number),
			origin: "EVENT",
			status: "COMPLETE",
			createdAt: daysAgo(days),
			completedAt: daysAgo(days),
		},
		positionOnWork: 1,
		totalOnWork: 1,
		results,
		feedback: [],
	});
	const reviews = [
		review(21, 1, [
			result("21a", 21, 1, FOCUS_SLUG, "MET", null),
			result("21b", 21, 1, "tour-example-diff-size", "MET", null),
			result("21c", 21, 1, "tour-example-respond", "MET", null),
		]),
		review(19, 4, [
			result("19a", 19, 4, FOCUS_SLUG, "NOT_MET", "The pull request has no description"),
			result("19b", 19, 4, "tour-example-diff-size", "MET", null),
		]),
		review(17, 9, [
			result("17a", 17, 9, FOCUS_SLUG, "NOT_MET", "The description names only what was added"),
			result("17b", 17, 9, "tour-example-one-concern", "MET", null),
		]),
	];
	const practiceReviews = reviews.map((each) => ({
		...each,
		results: each.results.filter((row) => row.practiceSlug === FOCUS_SLUG),
	}));

	const packagingAcross = acrossGroup(PACKAGING, split(4, 5, 6, 3), [
		[FOCUS_SLUG, split(5, 3, 7, 3)],
		["tour-example-one-concern", split(3, 6, 6, 3)],
		["tour-example-diff-size", split(2, 4, 9, 3)],
		["tour-example-commit-subjects", split(1, 3, 11, 3)],
	]);

	return {
		groups: EXAMPLE_PROFILE_GROUPS,
		practices,
		practicesByGroup,
		groupStandings: {
			[PACKAGING.slug]: groupStanding(PACKAGING, "DEVELOPING", "DECLINING", now),
			[ACTING.slug]: groupStanding(ACTING, "MIXED", "IMPROVING", now),
			[TESTING.slug]: groupStanding(TESTING, "NOT_OBSERVED", null, now),
		},
		latestReview,
		overview,
		feedbackCards: [openCard, resolvedCard],
		group: PACKAGING,
		practice,
		guidance: {
			practiceSlug: FOCUS_SLUG,
			visual: null,
			guide: {
				figures: {},
				markdown:
					"## Start with the problem\n\nOpen the description with one sentence on the problem that the change solves. Then say what changed, and how a reviewer can check it.\n\n## Going well\n\nA reviewer can say in their own words why the change is needed before they read the diff.",
			},
		},
		practiceReviews,
		reviews,
		across: {
			overview: {
				openFeedback: { yours: 1, middle: { low: 1, high: 3 } },
				includesEarlierReviews: false,
				groups: [
					packagingAcross,
					acrossGroup(ACTING, split(2, 6, 8, 2), [
						["tour-example-respond", split(3, 6, 7, 2)],
						["tour-example-resolve-threads", split(1, 2, 13, 2)],
					]),
					acrossGroup(TESTING, split(6, 4, 3, 5), [["tour-example-tests", split(6, 4, 3, 5)]]),
				],
			},
			tiles: {
				window: "DAYS_30",
				developersWithAStandingInWindow: 18,
				includesEarlierReviews: false,
				yourPractices: practices.length,
				reviewedWork: { yours: 3, middle: { low: 2, high: 6 } },
				practicesGoingWell: { yours: 3, middle: { low: 2, high: 4 } },
				practicesNeedingAttention: { yours: 1, middle: { low: 0, high: 2 } },
			},
			group: packagingAcross,
		},
	};
}
