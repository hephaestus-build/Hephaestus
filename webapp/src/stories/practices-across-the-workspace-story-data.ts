import type {
	PracticesAcrossWorkspace,
	PracticesAcrossWorkspaceTiles,
	WorkspaceGroupSplit,
	WorkspacePracticeSplit,
	WorkspaceSplit,
} from "@/api/types.gen";

type Group = Pick<WorkspaceGroupSplit, "groupSlug" | "groupName" | "groupIcon" | "groupColor">;

const ACTING: Group = {
	groupSlug: "acting-on-review-feedback",
	groupName: "Acting on review feedback",
	groupIcon: "MessageSquareReply",
	groupColor: "cyan",
};
const COMMUNICATION: Group = {
	groupSlug: "communication",
	groupName: "Communicating in the open",
	groupIcon: "MessageCircle",
	groupColor: "violet",
};
const FAILURE: Group = {
	groupSlug: "robust-error-handling",
	groupName: "Handling failure well",
	groupIcon: "ShieldAlert",
	groupColor: "amber",
};
const PACKAGING: Group = {
	groupSlug: "review-ready-work",
	groupName: "Packaging work for review",
	groupIcon: "Package",
	groupColor: "sky",
};
const REVIEWING: Group = {
	groupSlug: "constructive-code-review",
	groupName: "Reviewing a teammate's work constructively",
	groupIcon: "Eye",
	groupColor: "teal",
};
const TESTING: Group = {
	groupSlug: "testing-discipline",
	groupName: "Testing your changes",
	groupIcon: "TestTube",
	groupColor: "red",
};
const ISSUES: Group = {
	groupSlug: "actionable-issue-authoring",
	groupName: "Writing issues a maintainer can act on",
	groupIcon: "FileText",
	groupColor: "sky",
};
const MAINTAINABLE: Group = {
	groupSlug: "code-craftsmanship",
	groupName: "Writing maintainable code",
	groupIcon: "Wrench",
	groupColor: "emerald",
};

/** The developers with a standing every story split is a part of. */
const STORY_WITH_A_STANDING = 28;

/** A full split: Needs attention, Mixed feedback, Going well, and the rest of the 28 none yet. */
export const threeWay = ([needsAttention, mixedFeedback, goingWell]: [
	number,
	number,
	number,
]): WorkspaceSplit => ({
	shape: "SPLIT",
	parts: [
		{ standing: "DEVELOPING", developers: needsAttention },
		{ standing: "MIXED", developers: mixedFeedback },
		{ standing: "STRENGTH", developers: goingWell },
	],
	noneYet: STORY_WITH_A_STANDING - needsAttention - mixedFeedback - goingWell,
	developers: STORY_WITH_A_STANDING,
});

/** A split whose parts hold too few: only its total of 28 shows. */
export const TOTAL_ONLY: WorkspaceSplit = {
	shape: "TOTAL_ONLY",
	parts: [],
	developers: STORY_WITH_A_STANDING,
};

/** A split the server holds back whole, its total too: too few developers have a standing. */
export const WITHHELD: WorkspaceSplit = { shape: "WITHHELD", parts: [] };

const practice = (
	practiceSlug: string,
	practiceName: string,
	yourStanding: WorkspacePracticeSplit["yourStanding"],
	split: WorkspaceSplit,
): WorkspacePracticeSplit => ({ practiceSlug, practiceName, yourStanding, split });

const group = (
	of: Group,
	yourStanding: WorkspaceGroupSplit["yourStanding"],
	split: WorkspaceSplit,
	practices: WorkspacePracticeSplit[] = [],
): WorkspaceGroupSplit => ({ ...of, yourStanding, split, practices });

/**
 * Packaging's five practices: three split and two shown only as their total.
 * Every part shown holds more than K of the 28 developers with a standing, as CohortPrivacyPolicy requires.
 */
export const PACKAGING_PRACTICES: WorkspacePracticeSplit[] = [
	practice(
		"keep-the-diff-reviewable",
		"Keep the diff reviewable in one sitting",
		"DEVELOPING",
		threeWay([7, 6, 8]),
	),
	practice(
		"explain-the-change",
		"Explain what the change does and why",
		"MIXED",
		threeWay([6, 7, 8]),
	),
	practice("scope-to-one-concern", "Scope the change to one concern", undefined, TOTAL_ONLY),
	practice(
		"mark-ready-and-link",
		"Mark the change ready and link its issue",
		undefined,
		TOTAL_ONLY,
	),
	practice("keep-history-clean", "Keep the history readable", "STRENGTH", threeWay([6, 6, 9])),
];

/** More practices than the group's level holds, so its body has to scroll to its end. */
export const MANY_PRACTICES: WorkspacePracticeSplit[] = Array.from({ length: 24 }, (_, index) =>
	practice(`practice-${index + 1}`, `Practice ${index + 1}`, "MIXED", threeWay([6, 7, 8])),
);

/** The group the slide in stories open, with its practices. */
export const PACKAGING_GROUP: WorkspaceGroupSplit = group(
	PACKAGING,
	"DEVELOPING",
	threeWay([7, 6, 8]),
	PACKAGING_PRACTICES,
);

/**
 * Eight practice groups over 28 developers with a standing. Every part shown, none yet included, holds
 * more than K developers, as CohortPrivacyPolicy requires, so it stands for K besides any reader.
 */
export const ACROSS_WORKSPACE: PracticesAcrossWorkspace = {
	minimumOthers: 3,
	developersWithAStanding: 28,
	readerCounted: true,
	openFeedback: { yours: 3, middle: { low: 1, high: 4 } },
	groups: [
		group(ACTING, "MIXED", threeWay([6, 7, 7])),
		group(COMMUNICATION, "DEVELOPING", threeWay([7, 7, 6])),
		group(FAILURE, undefined, TOTAL_ONLY),
		PACKAGING_GROUP,
		group(REVIEWING, "STRENGTH", threeWay([6, 6, 8])),
		group(TESTING, "NOT_OBSERVED", threeWay([6, 6, 7])),
		group(ISSUES, "NO_OPPORTUNITY", threeWay([6, 6, 7])),
		group(MAINTAINABLE, undefined, TOTAL_ONLY),
	],
};

/** The reader's figures over the last 30 days beside the middle half of 26 developers with a standing. */
export const ACROSS_WORKSPACE_TILES: PracticesAcrossWorkspaceTiles = {
	window: "DAYS_30",
	minimumOthersForMiddleHalf: 6,
	developersWithAStandingInWindow: 26,
	yourPractices: 18,
	reviewedWork: { yours: 17, middle: { low: 11, high: 21 } },
	practicesGoingWell: { yours: 6, middle: { low: 5, high: 9 } },
	practicesNeedingAttention: { yours: 4, middle: { low: 2, high: 5 } },
};

/** Too few developers with a standing in the window for a middle half or their total. */
export const GATED_TILES: PracticesAcrossWorkspaceTiles = {
	...ACROSS_WORKSPACE_TILES,
	developersWithAStandingInWindow: undefined,
	reviewedWork: { yours: 17 },
	practicesGoingWell: { yours: 6 },
	practicesNeedingAttention: { yours: 4 },
};

/** Four other developers with a standing: too few for any figure about the workspace, even a total. */
export const GATED_WORKSPACE: PracticesAcrossWorkspace = {
	...ACROSS_WORKSPACE,
	developersWithAStanding: undefined,
	openFeedback: { yours: 3 },
	groups: ACROSS_WORKSPACE.groups.map((each) => ({
		...each,
		yourStanding: undefined,
		split: WITHHELD,
		practices: each.practices.map((one) => ({ ...one, yourStanding: undefined, split: WITHHELD })),
	})),
};

export const EMPTY_WORKSPACE: PracticesAcrossWorkspace = { ...ACROSS_WORKSPACE, groups: [] };

/** Many groups, every one listed. */
export const MANY_GROUPS_WORKSPACE: PracticesAcrossWorkspace = {
	...ACROSS_WORKSPACE,
	groups: Array.from({ length: 26 }, (_, index) => ({
		...group(
			{ ...ACTING, groupSlug: `group-${index + 1}`, groupName: `Practice group ${index + 1}` },
			"MIXED",
			threeWay([6, 7, 7]),
		),
	})),
};
