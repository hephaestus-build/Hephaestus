import type {
	PracticesAcrossWorkspace,
	PracticesAcrossWorkspaceTiles,
	WorkspaceGroupSplit,
	WorkspacePracticeSplit,
	WorkspaceSplit,
	WorkspaceTile,
} from "@/api/types.gen";

type GroupHead = Pick<WorkspaceGroupSplit, "groupSlug" | "groupName" | "groupIcon" | "groupColor">;

const ACTING: GroupHead = {
	groupSlug: "acting-on-review-feedback",
	groupName: "Acting on review feedback",
	groupIcon: "MessageSquareReply",
	groupColor: "cyan",
};
const COMMUNICATION: GroupHead = {
	groupSlug: "communication",
	groupName: "Communicating in the open",
	groupIcon: "MessageCircle",
	groupColor: "violet",
};
const PACKAGING: GroupHead = {
	groupSlug: "review-ready-work",
	groupName: "Packaging work for review",
	groupIcon: "Package",
	groupColor: "sky",
};
const TESTING: GroupHead = {
	groupSlug: "testing-discipline",
	groupName: "Testing your changes",
	groupIcon: "TestTube",
	groupColor: "red",
};

/** Developers at Needs attention, Mixed feedback and Going well, then those with none yet: one each. */
export const split = (
	needs: number,
	mixed: number,
	well: number,
	noneYet: number,
): WorkspaceSplit => ({
	parts: [
		{ standing: "DEVELOPING", developers: needs },
		{ standing: "MIXED", developers: mixed },
		{ standing: "STRENGTH", developers: well },
	],
	noneYet,
	developers: needs + mixed + well + noneYet,
});

/**
 * A split as the server sends it when a part would hold 1 to 4, or everyone sits in one part: its
 * total only. A null total is fewer than 5 developers.
 */
export const totalOnly = (developers: number | null): WorkspaceSplit => ({
	parts: [
		{ standing: "DEVELOPING", developers: null },
		{ standing: "MIXED", developers: null },
		{ standing: "STRENGTH", developers: null },
	],
	noneYet: null,
	developers,
});

const practice = (
	practiceSlug: string,
	practiceName: string,
	practiceSplit: WorkspaceSplit,
	yourStanding: WorkspacePracticeSplit["yourStanding"] = null,
	yourBasis: WorkspacePracticeSplit["yourBasis"] = null,
): WorkspacePracticeSplit => ({
	practiceSlug,
	practiceName,
	yourStanding,
	yourBasis,
	split: practiceSplit,
});

const PACKAGING_PRACTICES: WorkspacePracticeSplit[] = [
	practice(
		"keep-the-diff-reviewable",
		"Keep the diff reviewable in one sitting",
		split(9, 5, 7, 7),
		"DEVELOPING",
		"CURRENT_REVIEWS",
	),
	practice(
		"explain-the-change",
		"Explain what the change does and why",
		split(6, 7, 9, 6),
		"STRENGTH",
		"CURRENT_REVIEWS",
	),
	practice(
		"scope-to-one-concern",
		"Scope the change to one concern",
		totalOnly(28),
		"NOT_OBSERVED",
	),
	practice(
		"mark-ready-and-link",
		"Mark the change ready and link its issue",
		totalOnly(28),
		"NOT_OBSERVED",
	),
	practice(
		"keep-history-clean",
		"Keep the history readable",
		split(5, 5, 11, 7),
		"MIXED",
		"CURRENT_REVIEWS",
	),
];

const group = (
	head: GroupHead,
	groupSplit: WorkspaceSplit,
	practices: WorkspacePracticeSplit[],
	yourStanding: WorkspaceGroupSplit["yourStanding"] = null,
	yourBasis: WorkspaceGroupSplit["yourBasis"] = null,
): WorkspaceGroupSplit => ({ ...head, yourStanding, yourBasis, split: groupSplit, practices });

export const PACKAGING_GROUP: WorkspaceGroupSplit = group(
	PACKAGING,
	split(7, 6, 8, 7),
	PACKAGING_PRACTICES,
	"DEVELOPING",
	"CURRENT_REVIEWS",
);

/** 28 developers with a standing, the reader among them, each counted once. */
export const ACROSS_WORKSPACE: PracticesAcrossWorkspace = {
	openFeedback: { yours: 3, middle: null },
	includesEarlierReviews: false,
	groups: [
		group(
			ACTING,
			split(5, 6, 9, 8),
			[
				practice(
					"answer-every-comment",
					"Answer every review comment",
					split(5, 5, 10, 8),
					"STRENGTH",
					"CURRENT_REVIEWS",
				),
				practice(
					"re-request-review",
					"Ask for review again after changes",
					split(6, 5, 8, 9),
					"MIXED",
					"CURRENT_REVIEWS",
				),
			],
			"STRENGTH",
			"CURRENT_REVIEWS",
		),
		// A part under 5 hides the split: only the total shows; the reader has no verdict here.
		group(
			COMMUNICATION,
			totalOnly(28),
			[
				practice(
					"write-in-the-open",
					"Discuss decisions where the team can see them",
					totalOnly(28),
					"NOT_OBSERVED",
				),
			],
			"NOT_OBSERVED",
		),
		PACKAGING_GROUP,
		group(
			TESTING,
			split(10, 7, 5, 6),
			[
				practice(
					"test-the-change",
					"Add tests for the change",
					split(10, 7, 5, 6),
					"DEVELOPING",
					"CURRENT_REVIEWS",
				),
			],
			"DEVELOPING",
			"CURRENT_REVIEWS",
		),
	],
};

/** The reader's standings rest on earlier reviews in places, one of them with unknown criteria. */
export const EARLIER_REVIEWS_WORKSPACE: PracticesAcrossWorkspace = {
	...ACROSS_WORKSPACE,
	includesEarlierReviews: true,
	groups: ACROSS_WORKSPACE.groups.map((each) =>
		each.groupSlug === PACKAGING.groupSlug
			? {
					...each,
					yourBasis: "EARLIER_REVIEWS_CRITERIA_UNKNOWN",
					practices: each.practices.map((row, index) => {
						if (index === 0) {
							return {
								...row,
								yourBasis: "EARLIER_REVIEWS_CRITERIA_UNKNOWN",
							} satisfies WorkspacePracticeSplit;
						}
						if (index === 1) {
							return { ...row, yourBasis: "EARLIER_REVIEWS" } satisfies WorkspacePracticeSplit;
						}
						return row;
					}),
				}
			: each,
	),
};

/** The reader is the only developer with a standing: fewer than 5, so the split stays hidden. */
export const ONE_DEVELOPER_WORKSPACE: PracticesAcrossWorkspace = {
	openFeedback: { yours: 2, middle: null },
	includesEarlierReviews: false,
	groups: [
		group(
			PACKAGING,
			totalOnly(null),
			[
				practice(
					"keep-the-diff-reviewable",
					"Keep the diff reviewable in one sitting",
					totalOnly(null),
					"MIXED",
					"CURRENT_REVIEWS",
				),
			],
			"MIXED",
			"CURRENT_REVIEWS",
		),
	],
};

/** Two developers with a standing, the reader one of them: the split stays hidden. */
export const TWO_DEVELOPERS_WORKSPACE: PracticesAcrossWorkspace = {
	openFeedback: { yours: 0, middle: null },
	includesEarlierReviews: false,
	groups: [
		group(
			PACKAGING,
			totalOnly(null),
			[
				practice(
					"keep-the-diff-reviewable",
					"Keep the diff reviewable in one sitting",
					totalOnly(null),
					"STRENGTH",
					"CURRENT_REVIEWS",
				),
			],
			"STRENGTH",
			"CURRENT_REVIEWS",
		),
	],
};

/** The reader is not counted: no You bracket anywhere. */
export const READER_NOT_COUNTED_WORKSPACE: PracticesAcrossWorkspace = {
	...ACROSS_WORKSPACE,
	groups: ACROSS_WORKSPACE.groups.map((each) => ({
		...each,
		yourStanding: null,
		yourBasis: null,
		practices: each.practices.map((row) => ({ ...row, yourStanding: null, yourBasis: null })),
	})),
};

/** Nobody has a standing yet. */
export const NOBODY_WORKSPACE: PracticesAcrossWorkspace = {
	openFeedback: { yours: 0, middle: null },
	includesEarlierReviews: false,
	groups: ACROSS_WORKSPACE.groups.map((each) => ({
		...each,
		yourStanding: null,
		yourBasis: null,
		split: split(0, 0, 0, 0),
		practices: each.practices.map((row) => ({
			...row,
			yourStanding: null,
			yourBasis: null,
			split: split(0, 0, 0, 0),
		})),
	})),
};

export const EMPTY_WORKSPACE: PracticesAcrossWorkspace = {
	openFeedback: { yours: 0, middle: null },
	includesEarlierReviews: false,
	groups: [],
};

/** Many groups, every one listed. */
export const MANY_GROUPS_WORKSPACE: PracticesAcrossWorkspace = {
	...ACROSS_WORKSPACE,
	groups: Array.from({ length: 26 }, (_, index) =>
		group(
			{ ...ACTING, groupSlug: `group-${index + 1}`, groupName: `Practice group ${index + 1}` },
			split(5, 6, 9, 8),
			[practice(`practice-${index + 1}`, `Practice ${index + 1}`, split(5, 6, 9, 8))],
		),
	),
};

export const MANY_PRACTICES: WorkspacePracticeSplit[] = Array.from({ length: 24 }, (_, index) =>
	practice(`practice-${index + 1}`, `Practice ${index + 1}`, split(5, 6, 9, 8)),
);

const figure = (yours: number, low: number, high: number): WorkspaceTile => ({
	yours,
	middle: { low, high },
});

/** The tiles of each window, distinct so a test can tell which window a figure came from. */
export const TILES_BY_WINDOW: Record<
	PracticesAcrossWorkspaceTiles["window"],
	PracticesAcrossWorkspaceTiles
> = {
	DAYS_7: {
		window: "DAYS_7",
		developersWithAStandingInWindow: 12,
		yourPractices: 18,
		includesEarlierReviews: false,
		reviewedWork: figure(3, 1, 4),
		practicesGoingWell: figure(5, 3, 7),
		practicesNeedingAttention: figure(2, 1, 4),
	},
	DAYS_30: {
		window: "DAYS_30",
		developersWithAStandingInWindow: 21,
		yourPractices: 18,
		includesEarlierReviews: false,
		reviewedWork: figure(11, 6, 14),
		practicesGoingWell: figure(7, 4, 9),
		practicesNeedingAttention: figure(3, 2, 6),
	},
	DAYS_90: {
		window: "DAYS_90",
		developersWithAStandingInWindow: 26,
		yourPractices: 18,
		includesEarlierReviews: false,
		reviewedWork: figure(29, 17, 38),
		practicesGoingWell: figure(8, 5, 10),
		practicesNeedingAttention: figure(4, 2, 7),
	},
	ALL_TIME: {
		window: "ALL_TIME",
		developersWithAStandingInWindow: 28,
		yourPractices: 18,
		includesEarlierReviews: true,
		reviewedWork: figure(64, 41, 80),
		practicesGoingWell: figure(9, 5, 11),
		practicesNeedingAttention: figure(4, 3, 7),
	},
};

export const ACROSS_WORKSPACE_TILES = TILES_BY_WINDOW.DAYS_30;

/** One developer with a standing: fewer than 5, so the count and every middle half stay hidden. */
export const ONE_DEVELOPER_TILES: PracticesAcrossWorkspaceTiles = {
	...ACROSS_WORKSPACE_TILES,
	developersWithAStandingInWindow: null,
	reviewedWork: { yours: 4, middle: null },
	practicesGoingWell: { yours: 1, middle: null },
	practicesNeedingAttention: { yours: 0, middle: null },
};

/** Nobody has a standing in the window, so no tile has a middle half. */
export const NOBODY_TILES: PracticesAcrossWorkspaceTiles = {
	...ACROSS_WORKSPACE_TILES,
	developersWithAStandingInWindow: 0,
	reviewedWork: { yours: 0, middle: null },
	practicesGoingWell: { yours: 0, middle: null },
	practicesNeedingAttention: { yours: 0, middle: null },
};
