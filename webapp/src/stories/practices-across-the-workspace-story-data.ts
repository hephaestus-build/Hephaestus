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
		split(9, 4, 8, 7),
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
		split(1, 0, 2, 25),
		"NOT_OBSERVED",
	),
	practice(
		"mark-ready-and-link",
		"Mark the change ready and link its issue",
		split(0, 0, 0, 28),
		"NOT_OBSERVED",
	),
	practice(
		"keep-history-clean",
		"Keep the history readable",
		split(4, 5, 12, 7),
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
	openFeedback: { yours: 3, middle: { low: 1, high: 4 } },
	includesEarlierReviews: false,
	groups: [
		group(
			ACTING,
			split(4, 6, 10, 8),
			[
				practice(
					"answer-every-comment",
					"Answer every review comment",
					split(3, 5, 12, 8),
					"STRENGTH",
					"CURRENT_REVIEWS",
				),
				practice(
					"re-request-review",
					"Ask for review again after changes",
					split(6, 4, 9, 9),
					"MIXED",
					"CURRENT_REVIEWS",
				),
			],
			"STRENGTH",
			"CURRENT_REVIEWS",
		),
		// Parts of one, two and none draw like any other; the reader has no verdict here.
		group(
			COMMUNICATION,
			split(1, 0, 2, 25),
			[
				practice(
					"write-in-the-open",
					"Discuss decisions where the team can see them",
					split(1, 0, 2, 25),
					"NOT_OBSERVED",
				),
			],
			"NOT_OBSERVED",
		),
		PACKAGING_GROUP,
		group(
			TESTING,
			split(10, 9, 3, 6),
			[
				practice(
					"test-the-change",
					"Add tests for the change",
					split(10, 9, 3, 6),
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

/** The reader is the only developer with a standing. */
export const ONE_DEVELOPER_WORKSPACE: PracticesAcrossWorkspace = {
	openFeedback: { yours: 2, middle: { low: 2, high: 2 } },
	includesEarlierReviews: false,
	groups: [
		group(
			PACKAGING,
			split(0, 1, 0, 0),
			[
				practice(
					"keep-the-diff-reviewable",
					"Keep the diff reviewable in one sitting",
					split(0, 1, 0, 0),
					"MIXED",
					"CURRENT_REVIEWS",
				),
			],
			"MIXED",
			"CURRENT_REVIEWS",
		),
	],
};

/** Two developers with a standing, the reader one of them. */
export const TWO_DEVELOPERS_WORKSPACE: PracticesAcrossWorkspace = {
	openFeedback: { yours: 0, middle: { low: 1, high: 2 } },
	includesEarlierReviews: false,
	groups: [
		group(
			PACKAGING,
			split(1, 0, 1, 0),
			[
				practice(
					"keep-the-diff-reviewable",
					"Keep the diff reviewable in one sitting",
					split(1, 0, 1, 0),
					"STRENGTH",
					"CURRENT_REVIEWS",
				),
			],
			"STRENGTH",
			"CURRENT_REVIEWS",
		),
	],
};

/** Two developers are counted, and the reader is not one of them: no You bracket anywhere. */
export const READER_NOT_COUNTED_WORKSPACE: PracticesAcrossWorkspace = {
	...TWO_DEVELOPERS_WORKSPACE,
	openFeedback: { yours: 0, middle: { low: 1, high: 2 } },
	groups: TWO_DEVELOPERS_WORKSPACE.groups.map((each) => ({
		...each,
		yourStanding: null,
		yourBasis: null,
		practices: each.practices.map((row) => ({ ...row, yourStanding: null, yourBasis: null })),
	})),
};

/** Nobody has a standing yet. */
export const NOBODY_WORKSPACE: PracticesAcrossWorkspace = {
	openFeedback: { yours: 0, middle: { low: 0, high: 0 } },
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
			split(4, 6, 10, 8),
			[practice(`practice-${index + 1}`, `Practice ${index + 1}`, split(4, 6, 10, 8))],
		),
	),
};

export const MANY_PRACTICES: WorkspacePracticeSplit[] = Array.from({ length: 24 }, (_, index) =>
	practice(`practice-${index + 1}`, `Practice ${index + 1}`, split(4, 6, 10, 8)),
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

/** One developer with a standing: the middle half is that one value. */
export const ONE_DEVELOPER_TILES: PracticesAcrossWorkspaceTiles = {
	...ACROSS_WORKSPACE_TILES,
	developersWithAStandingInWindow: 1,
	reviewedWork: figure(4, 4, 4),
	practicesGoingWell: figure(1, 1, 1),
	practicesNeedingAttention: figure(0, 0, 0),
};

/** Nobody has a standing in the window, so no tile has a middle half. */
export const NOBODY_TILES: PracticesAcrossWorkspaceTiles = {
	...ACROSS_WORKSPACE_TILES,
	developersWithAStandingInWindow: 0,
	reviewedWork: { yours: 0, middle: null },
	practicesGoingWell: { yours: 0, middle: null },
	practicesNeedingAttention: { yours: 0, middle: null },
};
