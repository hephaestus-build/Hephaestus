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

/** A split: Needs attention, Mixed feedback, Going well, and the rest of the total none yet. */
export const threeWay = (
	[needsAttention, mixedFeedback, goingWell]: [number, number, number],
	total = STORY_WITH_A_STANDING,
): WorkspaceSplit => ({
	parts: [
		{ standing: "DEVELOPING", developers: needsAttention },
		{ standing: "MIXED", developers: mixedFeedback },
		{ standing: "STRENGTH", developers: goingWell },
	],
	noneYet: total - needsAttention - mixedFeedback - goingWell,
	developers: total,
});

/** A split with parts of one, two and nobody. */
export const SMALL_PARTS: WorkspaceSplit = threeWay([1, 0, 2]);

/** A split that counts nobody: no developer has a standing yet. */
export const NOBODY: WorkspaceSplit = threeWay([0, 0, 0], 0);

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

/** Packaging’s five practices, two of them with small parts. */
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
	practice("scope-to-one-concern", "Scope the change to one concern", "NOT_OBSERVED", SMALL_PARTS),
	practice(
		"mark-ready-and-link",
		"Mark the change ready and link its issue",
		"STRENGTH",
		threeWay([2, 1, 3]),
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

/** Eight practice groups over 28 developers with a standing, two of them with small parts. */
export const ACROSS_WORKSPACE: PracticesAcrossWorkspace = {
	openFeedback: { yours: 3, middle: { low: 1, high: 4 } },
	groups: [
		group(ACTING, "MIXED", threeWay([6, 7, 7])),
		group(COMMUNICATION, "DEVELOPING", threeWay([7, 7, 6])),
		group(FAILURE, "NOT_OBSERVED", SMALL_PARTS),
		PACKAGING_GROUP,
		group(REVIEWING, "STRENGTH", threeWay([6, 6, 8])),
		group(TESTING, "NOT_OBSERVED", threeWay([6, 6, 7])),
		group(ISSUES, "NO_OPPORTUNITY", threeWay([6, 6, 7])),
		group(MAINTAINABLE, "MIXED", threeWay([2, 3, 1])),
	],
};

/** The reader's figures over the last 30 days beside the middle half of 26 developers with a standing. */
export const ACROSS_WORKSPACE_TILES: PracticesAcrossWorkspaceTiles = {
	window: "DAYS_30",
	developersWithAStandingInWindow: 26,
	yourPractices: 18,
	reviewedWork: { yours: 17, middle: { low: 11, high: 21 } },
	practicesGoingWell: { yours: 6, middle: { low: 5, high: 9 } },
	practicesNeedingAttention: { yours: 4, middle: { low: 2, high: 5 } },
};

/** Nobody has a standing in the window, the reader included, so no tile has a middle half. */
export const NOBODY_TILES: PracticesAcrossWorkspaceTiles = {
	...ACROSS_WORKSPACE_TILES,
	developersWithAStandingInWindow: 0,
	yourPractices: 0,
	reviewedWork: { yours: 0 },
	practicesGoingWell: { yours: 0 },
	practicesNeedingAttention: { yours: 0 },
};

/** Nobody has a standing yet: every split counts nobody, and nobody has open feedback. */
export const NOBODY_WORKSPACE: PracticesAcrossWorkspace = {
	...ACROSS_WORKSPACE,
	openFeedback: { yours: 0, middle: { low: 0, high: 0 } },
	groups: ACROSS_WORKSPACE.groups.map((each) => ({
		...each,
		yourStanding: undefined,
		split: NOBODY,
		practices: each.practices.map((one) => ({ ...one, yourStanding: undefined, split: NOBODY })),
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
