import type { PracticesAcrossWorkspace, WorkspaceGroupSplit } from "@/api/types.gen";
import type { Estimate } from "@/components/practices-across-the-workspace/across-workspace-copy";

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

const split = (
	group: Group,
	yourStanding: WorkspaceGroupSplit["yourStanding"],
	[needsAttention, mixedFeedback, goingWell]: [number, number, number],
): WorkspaceGroupSplit => ({
	...group,
	yourStanding,
	shape: "SPLIT",
	needsAttention,
	mixedFeedback,
	goingWell,
});

/**
 * Eight practice groups over 24 of 31 developers, the numbers of design C. Every split leaves at
 * least five developers besides the reader without a standing, as the privacy rule requires, since
 * the observed total would otherwise give that rest away.
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
	groups: [
		split(ACTING, "MIXED", [5, 7, 7]),
		split(COMMUNICATION, "DEVELOPING", [6, 7, 6]),
		{ ...FAILURE, yourStanding: "MIXED", shape: "COLLAPSED", hasStanding: 19, noneYet: 5 },
		split(PACKAGING, "DEVELOPING", [6, 5, 8]),
		split(REVIEWING, "STRENGTH", [5, 6, 8]),
		split(TESTING, "NOT_OBSERVED", [5, 6, 7]),
		split(ISSUES, "NO_OPPORTUNITY", [5, 6, 7]),
		{ ...MAINTAINABLE, yourStanding: "STRENGTH", shape: "WITHHELD" },
	],
};

/** Three groups answered: two estimated, one skipped, as the design's halfway frame has them. */
export const HALFWAY_ESTIMATES: Record<string, Estimate> = {
	[ACTING.groupSlug]: "MIXED",
	[REVIEWING.groupSlug]: "SKIPPED",
	[PACKAGING.groupSlug]: "STRENGTH",
};

/** Every group answered: seven estimates and one skip. */
export const ALL_ESTIMATES: Record<string, Estimate> = {
	[ACTING.groupSlug]: "MIXED",
	[COMMUNICATION.groupSlug]: "MIXED",
	[FAILURE.groupSlug]: "DEVELOPING",
	[PACKAGING.groupSlug]: "STRENGTH",
	[REVIEWING.groupSlug]: "SKIPPED",
	[TESTING.groupSlug]: "STRENGTH",
	[ISSUES.groupSlug]: "MIXED",
	[MAINTAINABLE.groupSlug]: "STRENGTH",
};

/**
 * Four other developers observed: too few for any figure about the workspace, so every middle half
 * is missing and every split is withheld.
 */
export const GATED_WORKSPACE: PracticesAcrossWorkspace = {
	...ACROSS_WORKSPACE,
	observedDevelopers: 5,
	reviewedWork: { yours: 17 },
	practicesGoingWell: { yours: 6 },
	practicesNeedingAttention: { yours: 4 },
	groups: ACROSS_WORKSPACE.groups.map((group) => ({
		groupSlug: group.groupSlug,
		groupName: group.groupName,
		groupIcon: group.groupIcon,
		groupColor: group.groupColor,
		yourStanding: group.yourStanding,
		shape: "WITHHELD",
	})),
};

/** Enough developers for the tiles, but every group's three way split holds too few somewhere. */
export const COLLAPSED_WORKSPACE: PracticesAcrossWorkspace = {
	...ACROSS_WORKSPACE,
	groups: ACROSS_WORKSPACE.groups.map((group) => ({
		groupSlug: group.groupSlug,
		groupName: group.groupName,
		groupIcon: group.groupIcon,
		groupColor: group.groupColor,
		yourStanding: group.yourStanding,
		shape: "COLLAPSED",
		hasStanding: 17,
		noneYet: 7,
	})),
};

export const EMPTY_WORKSPACE: PracticesAcrossWorkspace = { ...ACROSS_WORKSPACE, groups: [] };
