import type { PracticeStanding } from "@/api/types.gen";
import { statusToneClass, statusValues } from "@/components/common/status-def";

import { PRACTICE_GROUP_STANDING_DEFS } from "./practice-group-standing-defs";

type Standing = PracticeStanding["standing"];
/**
 * Only the standing is read, so a practice the detail levels shape counts the same as a wire one.
 */
type HasStanding = Pick<PracticeStanding, "standing">;
/**
 * The two standings no review has settled draw in the legend's greys rather than a verdict's
 * colour.
 */
const RING_OPACITY: Partial<Record<Standing, string>> = {
	NOT_OBSERVED: "text-muted-foreground/75",
	NO_OPPORTUNITY: "text-muted-foreground/45",
};

/**
 * Every standing in registry order — what needs attention first — with the colour its arc and
 * legend line wear and the registry's short label.
 */
export const STANDING_SEGMENTS: readonly {
	standing: Standing;
	colorClass: string;
	label: string;
}[] = statusValues(PRACTICE_GROUP_STANDING_DEFS).map((standing) => ({
	standing,
	colorClass:
		RING_OPACITY[standing] ?? statusToneClass(PRACTICE_GROUP_STANDING_DEFS[standing].badgeVariant),
	label: PRACTICE_GROUP_STANDING_DEFS[standing].shortLabel,
}));

export type StandingCounts = Partial<Record<Standing, number>>;

export function countPracticeStandings(practices: readonly HasStanding[]): StandingCounts {
	const counts: StandingCounts = {};
	for (const practice of practices) {
		counts[practice.standing] = (counts[practice.standing] ?? 0) + 1;
	}
	return counts;
}

/**
 * Every standing with at least one practice, in registry order, with its colour and short label.
 */
export function summarizeStandingCounts(counts: StandingCounts) {
	return STANDING_SEGMENTS.map((segment) => ({
		...segment,
		count: counts[segment.standing] ?? 0,
	})).filter((segment) => segment.count > 0);
}
