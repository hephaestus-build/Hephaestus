import type { PracticesAcrossWorkspaceTiles, WorkspaceSplit } from "@/api/types.gen";
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

/**
 * How long the page waits before it says why it is still empty. The server counts a large workspace once
 * and keeps the count for every reader after, so only a reader of a workspace nobody read for a while
 * waits this long.
 */
export const SLOW_LOAD_AFTER_MS = 4000;

/** Shown while the counts take longer than {@link SLOW_LOAD_AFTER_MS}. */
export const SLOW_LOAD_NOTE =
	"Counting where everyone in the workspace stands takes a moment in a large course. The page fills in when the counts are ready.";

/**
 * How long the page keeps its counts before a refocus reads them again. The server keeps its counts
 * as long and drops them itself when new reviews arrive, so a sooner refetch would read the same.
 */
export const ACROSS_WORKSPACE_STALE_MS = 5 * 60 * 1000;

/** The overview failed: the bars and the practice groups' levels have nothing to show. */
export const GROUPS_LOAD_ERROR = "We could not load the practice groups";

/** Digits always: a count of developers sits beside other figures, never in running prose. */
const developers = (n: number) => count(n, "developer", "developers", true);

/**
 * The lines under the tiles, built only from the response so they hold for every reply: how many
 * developers the band sorts, and that open feedback, which reads no window, has a band of its own.
 * One paragraph for each.
 */
export function tilesHint({
	window,
	developersWithAStandingInWindow,
}: Pick<PracticesAcrossWorkspaceTiles, "window" | "developersWithAStandingInWindow">): readonly [
	string,
	string,
] {
	const open = "Open feedback counts what is open now, for every developer that this page counts.";
	if (developersWithAStandingInWindow === 0) {
		return [
			`No developer in this workspace has a standing ${windowPhrase(window)}, so these figures have no typical range.`,
			open,
		];
	}
	const band = [
		"The band is the typical range.",
		`Hephaestus sorts the ${developers(developersWithAStandingInWindow)} in this workspace who ${developersWithAStandingInWindow === 1 ? "has" : "have"} a standing ${windowPhrase(window)} by their value.`,
		"The band covers the middle half: a quarter of them are below it, and a quarter are above it.",
		"Your marker shows your value.",
		"When only a few developers are counted, the band can show the value of one person.",
	].join(" ");
	return [band, open];
}

/** The line over a table of bars, in the words of `docs/user/practice-profile.mdx` § The bars. */
export function barsHint(scope: StandingScope): string {
	return [
		`Each bar counts developers by their current standing in the ${scope}, as their Practice profile shows it.`,
		"The You marker shows your part.",
		"Every bar shows all its counts, however small.",
		"So a small count can let others tell where you stand.",
	].join(" ");
}

/** Under the empty track of a split that counts nobody: no developer has a standing yet. */
export const NOBODY_YET = "No developer has a standing yet";

/** "24 developers", as the bar prints a split's total. */
export const splitTotalText = (split: WorkspaceSplit): string => developers(split.developers);

/**
 * The bar's text alternative: the reference group, every count in the registry's words and the
 * server's order, and the part the You marker is on, so the bar's text says what its legend says.
 */
export function splitDescription(
	split: WorkspaceSplit,
	yourStanding: PracticeGroupStandingValue | undefined,
): string {
	if (split.developers === 0) {
		return `${NOBODY_YET}.`;
	}
	const whole = `${splitTotalText(split)} with a current standing in this workspace`;
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
