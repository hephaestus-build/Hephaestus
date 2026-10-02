import type {
	PracticesAcrossWorkspace,
	WorkspaceGroupSplit,
	WorkspacePracticeSplit,
	WorkspaceSplit,
} from "@/api/types.gen";

import { daysBefore, STORY_NOW } from "./story-clock";

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

/** A three way split: Needs attention, Mixed feedback, Going well. */
export const threeWay = ([needsAttention, mixedFeedback, goingWell]: [
	number,
	number,
	number,
]): WorkspaceSplit => ({ shape: "SPLIT", needsAttention, mixedFeedback, goingWell });

export const collapsed = (hasStanding: number, noneYet: number): WorkspaceSplit => ({
	shape: "COLLAPSED",
	hasStanding,
	noneYet,
});

export const WITHHELD: WorkspaceSplit = { shape: "WITHHELD" };

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

/** Packaging's five practices: two split, one collapsed, one held back with its total, one more. */
export const PACKAGING_PRACTICES: WorkspacePracticeSplit[] = [
	practice(
		"keep-the-diff-reviewable",
		"Keep the diff reviewable in one sitting",
		"DEVELOPING",
		threeWay([7, 6, 6]),
	),
	practice(
		"explain-the-change",
		"Explain what the change does and why",
		"MIXED",
		threeWay([5, 7, 7]),
	),
	practice("scope-to-one-concern", "Scope the change to one concern", "STRENGTH", collapsed(16, 8)),
	practice(
		"mark-ready-and-link",
		"Mark the change ready and link its issue",
		"NOT_OBSERVED",
		WITHHELD,
	),
	practice("keep-history-clean", "Keep the history readable", "STRENGTH", threeWay([5, 5, 9])),
];

const TREND = {
	yourDirection: "IMPROVING",
	yourTrendSupport: {
		currentOpportunities: 6,
		previousOpportunities: 5,
		opportunities: 11,
		opportunitiesUntilComparable: 0,
		calendarSpanDays: 12,
		bundleSize: 4,
		ropeHalfWidth: 0.15,
		credibilityThreshold: 0.9,
	},
} as const satisfies Pick<WorkspaceGroupSplit, "yourDirection" | "yourTrendSupport">;

/** The group the slide in stories open: its practices and the reader's trend in it. */
export const PACKAGING_GROUP: WorkspaceGroupSplit = {
	...group(PACKAGING, "DEVELOPING", threeWay([6, 5, 8]), PACKAGING_PRACTICES),
	...TREND,
};

/**
 * Eight practice groups over 24 of 31 developers. Every split leaves at least five developers
 * besides the reader without a standing, as the privacy rule requires, since the observed total
 * would otherwise give that rest away.
 */
export const ACROSS_WORKSPACE: PracticesAcrossWorkspace = {
	window: "TERM",
	since: daysBefore(90),
	until: new Date(STORY_NOW),
	minimumOthers: 5,
	eligibleDevelopers: 31,
	observedDevelopers: 24,
	readerCounted: true,
	yourPractices: 18,
	reviewedWork: { yours: 17, middleLow: 11, middleHigh: 21 },
	practicesGoingWell: { yours: 6, middleLow: 5, middleHigh: 9 },
	practicesNeedingAttention: { yours: 4, middleLow: 2, middleHigh: 5 },
	openFeedback: { yours: 3, middleLow: 1, middleHigh: 4 },
	groups: [
		group(ACTING, "MIXED", threeWay([5, 7, 7])),
		group(COMMUNICATION, "DEVELOPING", threeWay([6, 7, 6])),
		group(FAILURE, "MIXED", collapsed(19, 5)),
		PACKAGING_GROUP,
		group(REVIEWING, "STRENGTH", threeWay([5, 6, 8])),
		group(TESTING, "NOT_OBSERVED", threeWay([5, 6, 7])),
		group(ISSUES, "NO_OPPORTUNITY", threeWay([5, 6, 7])),
		group(MAINTAINABLE, "STRENGTH", WITHHELD),
	],
};

/** Four other developers observed: too few for any figure about the workspace, even a total. */
export const GATED_WORKSPACE: PracticesAcrossWorkspace = {
	...ACROSS_WORKSPACE,
	observedDevelopers: 5,
	reviewedWork: { yours: 17 },
	practicesGoingWell: { yours: 6 },
	practicesNeedingAttention: { yours: 4 },
	openFeedback: { yours: 3 },
	groups: ACROSS_WORKSPACE.groups.map((each) => ({
		...each,
		split: WITHHELD,
		practices: each.practices.map((one) => ({ ...one, split: WITHHELD })),
	})),
};

/** Enough developers for the tiles, but every group's three way split holds too few somewhere. */
export const COLLAPSED_WORKSPACE: PracticesAcrossWorkspace = {
	...ACROSS_WORKSPACE,
	groups: ACROSS_WORKSPACE.groups.map((each) => ({ ...each, split: collapsed(17, 7) })),
};

/** Every group held back with only the observed total: has a standing or none yet holds too few. */
export const TOTAL_ONLY_WORKSPACE: PracticesAcrossWorkspace = {
	...ACROSS_WORKSPACE,
	groups: ACROSS_WORKSPACE.groups.map((each) => ({ ...each, split: WITHHELD })),
};

export const EMPTY_WORKSPACE: PracticesAcrossWorkspace = { ...ACROSS_WORKSPACE, groups: [] };

/** More groups than one page holds, so the table shows its end. */
export const MANY_GROUPS_WORKSPACE: PracticesAcrossWorkspace = {
	...ACROSS_WORKSPACE,
	groups: Array.from({ length: 26 }, (_, index) => ({
		...group(
			{ ...ACTING, groupSlug: `group-${index + 1}`, groupName: `Practice group ${index + 1}` },
			"MIXED",
			threeWay([5, 7, 7]),
		),
	})),
};
