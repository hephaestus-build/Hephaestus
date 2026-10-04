import { CircleIcon } from "lucide-react";

import type { PracticeStanding } from "@/api/types.gen";
import { statusToneClass, statusValues } from "@/components/common/status-def";
import { capitalise } from "@/lib/text";

import { PRACTICE_GROUP_STANDING_DEFS } from "./practice-group-standing-defs";

type Standing = PracticeStanding["standing"];
type HasStanding = Pick<PracticeStanding, "standing">;
/**
 * The grey for a meaning-bearing part that is no verdict. Applied as a text colour and painted with
 * `currentColor`. Measured against `bg-background`, it holds the 3:1 that WCAG 2.2 SC 1.4.11 asks
 * of a chart part: 3.1:1 in light and 4.6:1 in dark. Any lighter fails in light.
 */
export const NEUTRAL_GREY = "text-muted-foreground/75";

/** The two standings no review has settled take greys, not a verdict's colour. */
const RING_OPACITY: Partial<Record<Standing, string>> = {
	NOT_OBSERVED: NEUTRAL_GREY,
	NO_OPPORTUNITY: "text-muted-foreground/45",
};

/** The colour a standing's arc, bar part and legend line wear. */
export const standingColorClass = (standing: Standing): string =>
	RING_OPACITY[standing] ?? statusToneClass(PRACTICE_GROUP_STANDING_DEFS[standing].badgeVariant);

/** Every standing in registry order, with its colour and short label. */
export const STANDING_SEGMENTS: readonly {
	standing: Standing;
	colorClass: string;
	label: string;
}[] = statusValues(PRACTICE_GROUP_STANDING_DEFS).map((standing) => ({
	standing,
	colorClass: standingColorClass(standing),
	label: PRACTICE_GROUP_STANDING_DEFS[standing].shortLabel,
}));

const NONE_YET = "none yet";

/**
 * Developers with no standing in a subject, for either reason that is no verdict. It holds both, so
 * it takes a neutral empty circle rather than either one's icon.
 */
export const NONE_YET_SEGMENT = {
	label: capitalise(NONE_YET),
	/** The label inside a sentence: "7 none yet". */
	inSentence: NONE_YET,
	icon: CircleIcon,
	colorClass: NEUTRAL_GREY,
};

export type StandingCounts = Partial<Record<Standing, number>>;

export function countPracticeStandings(practices: readonly HasStanding[]): StandingCounts {
	const counts: StandingCounts = {};
	for (const practice of practices) {
		counts[practice.standing] = (counts[practice.standing] ?? 0) + 1;
	}
	return counts;
}

/** Every standing with at least one practice, in registry order. */
export function summarizeStandingCounts(counts: StandingCounts) {
	return STANDING_SEGMENTS.map((segment) => ({
		...segment,
		count: counts[segment.standing] ?? 0,
	})).filter((segment) => segment.count > 0);
}
