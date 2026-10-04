import type { PracticesAcrossWorkspaceTiles, WorkspaceSplit, WorkspaceTile } from "@/api/types.gen";
import { ACTIVITY_RANGE_DEFS, type ActivityRange } from "@/components/activity/activity-range";
import type { FilterOption } from "@/components/common/FilterToggle";
import { statusValues } from "@/components/common/status-def";
import { count } from "@/components/practice-vocabulary/feedback-text";
import {
	isSettledStanding,
	PRACTICE_GROUP_STANDING_DEFS,
	type PracticeGroupStandingValue,
	type StandingScope,
} from "@/components/practice-vocabulary/practice-group-standing-defs";
import { NONE_YET_SEGMENT } from "@/components/practice-vocabulary/standing-counts";

export type AcrossWorkspaceWindow = PracticesAcrossWorkspaceTiles["window"];

interface WindowDef {
	label: string;
	shortLabel: string;
	/** The window as the tail of a sentence: "with a standing so far". */
	phrase: string;
}

function activityWindow(range: ActivityRange): WindowDef {
	const def = ACTIVITY_RANGE_DEFS[range];
	return { label: def.label, shortLabel: def.shortLabel, phrase: `in ${def.inSentence}` };
}

/**
 * Shortest first, as Apple Health orders its ranges, and in Activity's words for the same span. A
 * total `Record`, so a window the server adds fails to compile instead of rendering blank.
 */
const WINDOW_DEFS: Record<AcrossWorkspaceWindow, WindowDef> = {
	DAYS_30: activityWindow("30d"),
	DAYS_90: activityWindow("90d"),
	ALL_TIME: { label: "All time", shortLabel: "All time", phrase: "so far" },
};

export const WINDOW_VALUES = statusValues(WINDOW_DEFS);

export const WINDOW_OPTIONS: readonly FilterOption<AcrossWorkspaceWindow>[] = WINDOW_VALUES.map(
	(value) => ({
		value,
		label: WINDOW_DEFS[value].label,
		shortLabel: WINDOW_DEFS[value].shortLabel,
	}),
);

export const DEFAULT_WINDOW: AcrossWorkspaceWindow = "DAYS_30";

export const windowPhrase = (window: AcrossWorkspaceWindow): string => WINDOW_DEFS[window].phrase;

export const windowHeading = (window: AcrossWorkspaceWindow): string => WINDOW_DEFS[window].label;

/** The page's short name: its sidebar entry and the first crumb of its levels. */
export const ACROSS_THE_WORKSPACE = "Across the workspace";

/** One action from the group's header and from each practice row, so one label. */
export const OPEN_IN_YOUR_PROFILE = "Open in your Practice profile";

/** Digits always: a count of developers sits beside other figures, never in running prose. */
const developers = (n: number) => count(n, "developer", "developers", true);
const otherDevelopers = (n: number) => count(n, "other developer", "other developers", true);

/**
 * The lines under the tiles. Built only from the response, so they hold for every reply: no count
 * where the server held the total back, the server's threshold, and a line on whether open
 * feedback, which reads no window, has a band of its own. One paragraph for each.
 */
export function tilesHint(
	{
		minimumOthersForMiddleHalf,
		window,
		developersWithAStandingInWindow,
	}: Pick<
		PracticesAcrossWorkspaceTiles,
		"minimumOthersForMiddleHalf" | "window" | "developersWithAStandingInWindow"
	>,
	openFeedback: WorkspaceTile,
): readonly [string, string] {
	const sorted =
		developersWithAStandingInWindow === undefined
			? "the developers"
			: `the ${developers(developersWithAStandingInWindow)}`;
	const threshold = otherDevelopers(minimumOthersForMiddleHalf);
	const band = [
		"The grey band is the typical range.",
		`To find it, Hephaestus takes ${sorted} in this workspace who ${developersWithAStandingInWindow === 1 ? "has" : "have"} a standing ${windowPhrase(window)}, and sorts them by their value.`,
		"The band covers the middle half: a quarter of them are below it, and a quarter are above it.",
		"Your marker shows your value.",
		`A tile shows the band only when at least ${threshold} ${minimumOthersForMiddleHalf === 1 ? "has" : "have"} a standing.`,
	].join(" ");
	const open = "Open feedback counts what is open now, for every developer that this page counts.";
	return [
		band,
		openFeedback.middle === undefined
			? `${open} Its band shows only when this page counts at least ${threshold}.`
			: open,
	];
}

/**
 * The line over a table of bars, in the words of `docs/user/practice-profile.mdx` § The bars. The
 * server shows a part only when it holds more than `minimumOthers` developers with the reader
 * counted, so the hint names that floor and the others it leaves whoever reads the bar.
 */
export function barsHint(minimumOthers: number, scope: StandingScope): string {
	const others = `${developers(minimumOthers)} other than you`;
	const rule = [
		`Each bar counts developers by their current standing in the ${scope}, as their Practice profile shows it.`,
		"The You marker shows your part.",
		`A bar shows its parts only when each part holds at least ${developers(minimumOthers + 1)}.`,
		`So each part stands for at least ${others}.`,
		"If a part would hold fewer, the bar shows only its number of developers.",
	];
	if (scope === "practice") {
		rule.push(
			`A practice’s bar also shows only its number if, beside its group’s bar, it would single out fewer than ${developers(minimumOthers)}.`,
		);
	}
	return rule.join(" ");
}

/**
 * Under the empty track of a split held back whole. It promises nothing about later: more data does
 * not lift every reason the privacy rule holds a split back.
 */
export const HELD_BACK = "Held back so no one can be singled out";

/** Under the neutral bar of a split shown only as its total, for every reason the parts are held back. */
export const SPLIT_HELD_BACK = "Split held back";

/** A shown split's total as the bar prints it: "24 developers". The server sets it for every shape but `WITHHELD`. */
export const splitTotalText = (split: WorkspaceSplit): string => developers(split.developers ?? 0);

/**
 * The bar's text alternative: the reference group, every count in the registry's words and the
 * server's order, and the part the You marker is on, so the bar's text says what its legend says.
 */
export function splitDescription(
	split: WorkspaceSplit,
	yourStanding: PracticeGroupStandingValue,
	readerCounted: boolean,
): string {
	if (split.shape === "WITHHELD") {
		return `${HELD_BACK}.`;
	}
	const whole = `${splitTotalText(split)} with a current standing in this workspace`;
	if (split.shape === "TOTAL_ONLY") {
		return `${whole}. The split is held back so no one can be singled out.`;
	}
	const parts = split.parts.map(
		(part) => `${part.developers} ${PRACTICE_GROUP_STANDING_DEFS[part.standing].label}`,
	);
	const noneYet = `${split.noneYet ?? 0} ${NONE_YET_SEGMENT.inSentence}`;
	const yourPart = isSettledStanding(yourStanding)
		? PRACTICE_GROUP_STANDING_DEFS[yourStanding].label
		: NONE_YET_SEGMENT.inSentence;
	const marker = readerCounted ? ` The You marker is on ${yourPart}.` : "";
	return `${whole}: ${[...parts, noneYet].join(", ")}.${marker}`;
}
