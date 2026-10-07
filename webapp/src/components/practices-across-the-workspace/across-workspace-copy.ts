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

/** Delay before the page explains a slow read. */
export const SLOW_LOAD_AFTER_MS = 4000;

/** Shown while the counts take longer than {@link SLOW_LOAD_AFTER_MS}. */
export const SLOW_LOAD_NOTE =
	"Counting where everyone in the workspace stands takes a moment in a large course. The page fills in when the counts are ready.";

/** Keep completed queries fresh across window changes and brief focus changes. */
export const ACROSS_WORKSPACE_STALE_MS = 5 * 60 * 1000;

/** The overview failed: the bars and the practice groups' levels have nothing to show. */
export const GROUPS_LOAD_ERROR = "We could not load the practice groups";

/** Digits always: a count of developers sits beside other figures, never in running prose. */
const developers = (n: number) => count(n, "developer", "developers", true);

/** A floor the server sends counts the reader too, so the floor less one is the others it stands for. */
const othersBeyond = (floor: number) => floor - 1;

/**
 * The lines under the tiles, built only from the response so they hold for every reply: no count
 * where the server held the total back, the band's floor, and whether open feedback, which reads
 * no window, has a band of its own. One paragraph for each.
 */
export function tilesHint(
	{
		minimumDevelopersForMiddleHalf,
		window,
		developersWithAStandingInWindow,
	}: Pick<
		PracticesAcrossWorkspaceTiles,
		"minimumDevelopersForMiddleHalf" | "window" | "developersWithAStandingInWindow"
	>,
	openFeedback: WorkspaceTile,
): readonly [string, string] {
	const sorted =
		developersWithAStandingInWindow === undefined
			? "the developers"
			: `the ${developers(developersWithAStandingInWindow)}`;
	const floor = developers(minimumDevelopersForMiddleHalf);
	const others = `${developers(othersBeyond(minimumDevelopersForMiddleHalf))} other than you`;
	const band = [
		"The band is the typical range.",
		`Hephaestus sorts ${sorted} in this workspace who ${developersWithAStandingInWindow === 1 ? "has" : "have"} a standing ${windowPhrase(window)} by their value.`,
		"The band covers the middle half: a quarter of them are below it, and a quarter are above it.",
		"Your marker shows your value.",
		`The band shows only when at least ${floor} are counted, so at least ${others}.`,
	].join(" ");
	const open = "Open feedback counts what is open now, for every developer that this page counts.";
	return [
		band,
		openFeedback.middle === undefined
			? `${open} Its band shows only when this page counts at least ${floor}.`
			: open,
	];
}

/** The line over a table of bars, in the words of `docs/user/practice-profile.mdx` § The bars. */
export function barsHint(minimumDevelopersPerCount: number, scope: StandingScope): string {
	const floor = developers(minimumDevelopersPerCount);
	const others = othersBeyond(minimumDevelopersPerCount);
	const singledOut = `one to ${count(others, "developer", "developers", false)}`;
	return [
		`Each bar counts developers by their current standing in the ${scope}, as their Practice profile shows it.`,
		"The You marker shows your part.",
		`A count shows only when it holds at least ${floor}.`,
		`So each count stands for at least ${developers(others)} other than you, whoever reads it, and every reader sees the same bars.`,
		"If a part would hold fewer, the bar shows only its number of developers.",
		...(scope === "group"
			? [
					`If the groups together would single out ${singledOut}, every bar on the page shows only its number.`,
				]
			: [
					"A practice bar is compared with its group’s bar and the group’s other practice bars.",
					`If that would single out ${singledOut}, it shows only its number.`,
				]),
	].join(" ");
}

/**
 * Under the empty track of a split held back whole. It promises nothing about later: more data does
 * not lift every reason the privacy rule holds a split back.
 */
export const HELD_BACK = "Held back so no one can be singled out";

/** Under the neutral bar of a split shown only as its total, for every reason the parts are held back. */
export const SPLIT_HELD_BACK = "Split held back";

/** A split the page may draw, with the counts its shape promises. */
export type ShownSplit =
	| { shape: "SPLIT"; parts: WorkspaceSplit["parts"]; developers: number; noneYet: number }
	| { shape: "TOTAL_ONLY"; developers: number };

/**
 * The wire's split narrowed by its shape. The DTO types every count as optional, so a split
 * missing a count its shape promises is held back rather than drawn with an invented 0.
 */
export function shownSplit({
	shape,
	parts,
	developers: total,
	noneYet,
}: WorkspaceSplit): ShownSplit | undefined {
	if (shape === "WITHHELD" || total === undefined) {
		return undefined;
	}
	if (shape === "TOTAL_ONLY") {
		return { shape, developers: total };
	}
	return noneYet === undefined ? undefined : { shape, parts, developers: total, noneYet };
}

/** "24 developers", as the bar prints a shown split's total. */
export const splitTotalText = (split: ShownSplit): string => developers(split.developers);

/**
 * The bar's text alternative: the reference group, every count in the registry's words and the
 * server's order, and the part the You marker is on, so the bar's text says what its legend says.
 */
export function splitDescription(
	wire: WorkspaceSplit,
	yourStanding: PracticeGroupStandingValue | undefined,
): string {
	const split = shownSplit(wire);
	if (split === undefined) {
		return `${HELD_BACK}.`;
	}
	const whole = `${splitTotalText(split)} with a current standing in this workspace`;
	if (split.shape === "TOTAL_ONLY") {
		return `${whole}. The split is held back so no one can be singled out.`;
	}
	const parts = split.parts.map(
		(part) => `${part.developers} ${PRACTICE_GROUP_STANDING_DEFS[part.standing].label}`,
	);
	const noneYet = `${split.noneYet} ${NONE_YET_SEGMENT.inSentence}`;
	const counts = `${whole}: ${[...parts, noneYet].join(", ")}.`;
	if (yourStanding === undefined) {
		return counts;
	}
	const yourPart = isSettledStanding(yourStanding)
		? PRACTICE_GROUP_STANDING_DEFS[yourStanding].label
		: NONE_YET_SEGMENT.inSentence;
	return `${counts} The You marker is on ${yourPart}.`;
}
