import { CircleIcon } from "lucide-react";

import type { PracticeStanding } from "@/api/types.gen";
import { statusToneClass, statusValues } from "@/components/common/status-def";
import { capitalise } from "@/lib/text";

import { PRACTICE_GROUP_STANDING_DEFS } from "./practice-group-standing-defs";

type Standing = PracticeStanding["standing"];
/**
 * Only the standing is read, so a practice the detail levels shape counts the same as a wire one.
 */
type HasStanding = Pick<PracticeStanding, "standing">;
/**
 * The two neutral greys every standing chart draws what no review settled in: the stronger one
 * stays a part of a chart against the card, the fainter one sits behind it. A chart applies them
 * as a text colour and paints with `currentColor`, so a fill, a stroke and a border share them.
 */
export const NEUTRAL_GREY = "text-muted-foreground/75";
export const FAINT_GREY = "text-muted-foreground/45";

/**
 * The two standings no review has settled draw in the legend's greys rather than a verdict's
 * colour.
 */
const RING_OPACITY: Partial<Record<Standing, string>> = {
	NOT_OBSERVED: NEUTRAL_GREY,
	NO_OPPORTUNITY: FAINT_GREY,
};

/** The colour a standing's arc, bar part and legend line wear. */
export const standingColorClass = (standing: Standing): string =>
	RING_OPACITY[standing] ?? statusToneClass(PRACTICE_GROUP_STANDING_DEFS[standing].badgeVariant);

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
	colorClass: standingColorClass(standing),
	label: PRACTICE_GROUP_STANDING_DEFS[standing].shortLabel,
}));

const NONE_YET = "none yet";

/**
 * Where a count of developers puts everyone with no standing yet, whatever the reason: both
 * standings that are no verdict. It holds both silences, so it takes a neutral empty circle rather
 * than either one's icon, and the fainter grey.
 */
export const NONE_YET_SEGMENT = {
	label: capitalise(NONE_YET),
	/** The label inside a sentence: "7 none yet". */
	inSentence: NONE_YET,
	icon: CircleIcon,
	colorClass: FAINT_GREY,
};

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
