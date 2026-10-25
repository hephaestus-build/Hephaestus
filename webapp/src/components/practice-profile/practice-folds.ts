// The words and the arithmetic behind the fold of the Practice profile: practices and groups with no
// standing yet, because they never applied to the developer's work or no review has reviewed them.
// Each reads only what the developer's own page already holds. Nothing here names another developer.

import { CircleDashedIcon } from "lucide-react";

import type { PracticeStanding } from "@/api/types.gen";
import type { StatusDef } from "@/components/common/status-def";
import {
	isSettledStanding,
	standingDefs,
} from "@/components/practice-vocabulary/practice-group-standing-defs";
import { LOOKBACK_DAYS } from "@/components/practice-vocabulary/practice-trend-presentation";

// The server reads only this window, so every sentence names it: older evidence does not count.
const WINDOW = `in the last ${LOOKBACK_DAYS} days`;

export const NEVER_APPLIED = {
	description: `Every review ${WINDOW} found that this practice did not apply to your work. This is not a gap: it gets a standing once a piece of work calls for it.`,
	/** The sentence behind one folded group's badge. */
	oneGroupDescription: `Every review ${WINDOW} found that the practices of this group did not apply to your work. This is not a gap.`,
} as const;

/**
 * The one fold of practices or groups without a standing, in the reader's words. A developer does
 * not need to tell "did not apply" from "not reviewed yet": both mean no review has settled a
 * standing, so both read "No standing yet", as the page's counts say it. The badge's sentence keeps
 * why, for a reader who asks. Admin surfaces keep both.
 */
export const NO_STANDING_YET = {
	label: "No standing yet",
	practiceLabel: "Practices with no standing yet",
	groupLabel: "Practice groups with no standing yet",
	practiceDescription: `No review ${WINDOW} has given these practices a standing in your work. This is not a gap.`,
	groupDescription: `No review ${WINDOW} has given a practice of these groups a standing in your work. This is not a gap: open a group to see why.`,
	/** The sentence behind one group's badge when none of its practices has a standing. */
	oneGroupDescription: `No review ${WINDOW} has given a practice of this group a standing in your work. This is not a gap.`,
} as const;

/** The badge of a practice or group without a standing; its sentence says why, per row. */
export const NO_STANDING_YET_DEF: StatusDef = {
	label: NO_STANDING_YET.label,
	icon: CircleDashedIcon,
	badgeVariant: "outline",
	description: NO_STANDING_YET.practiceDescription,
};

export const isNeverApplied = (practice: PracticeStanding): boolean =>
	practice.standing === "NO_OPPORTUNITY" && practice.doesNotApplyOnly;

/** No review has settled a standing on the practice: it did not apply, or nobody reviewed it. */
export const hasNoStandingYet = (practice: Pick<PracticeStanding, "standing">): boolean =>
	!isSettledStanding(practice.standing);

/** The ones the table lists, and the ones with no standing yet, which fold into one line under it. */
export function splitFoldedPractices<T extends PracticeStanding>(
	practices: readonly T[],
): { shown: T[]; noStandingYet: T[] } {
	return {
		shown: practices.filter((practice) => !hasNoStandingYet(practice)),
		noStandingYet: practices.filter(hasNoStandingYet),
	};
}

/** A group in which every practice never applied. */
export const groupNeverApplied = (practices: readonly PracticeStanding[]): boolean =>
	practices.length > 0 && practices.every(isNeverApplied);

/** A group in which no practice has a standing yet, whatever the reason for each. */
export const groupHasNoStandingYet = (practices: readonly PracticeStanding[]): boolean =>
	practices.length > 0 && practices.every(hasNoStandingYet);

/**
 * Why a practice has no standing yet: the sentence behind its "No standing yet" badge. A practice
 * that never applied says so; otherwise the standing's own sentence does.
 */
export function practiceNoStandingYetReason(practice: PracticeStanding): string {
	return isNeverApplied(practice)
		? NEVER_APPLIED.description
		: standingDefs("practice")[practice.standing].description;
}

/**
 * Why a group has no standing yet, from the same practices that fold it: every practice never
 * applied, or none has a standing. Only a group whose practices have a standing but whose own
 * standing does not falls back to the group standing's sentence, so the sentence never praises or
 * blames work the badge says has no standing.
 */
export function groupNoStandingYetReason(
	practices: readonly PracticeStanding[],
	standing: PracticeStanding["standing"] | undefined,
): string {
	if (groupNeverApplied(practices)) {
		return NEVER_APPLIED.oneGroupDescription;
	}
	if (groupHasNoStandingYet(practices) || standing === undefined || isSettledStanding(standing)) {
		return NO_STANDING_YET.oneGroupDescription;
	}
	return standingDefs("group")[standing].description;
}
