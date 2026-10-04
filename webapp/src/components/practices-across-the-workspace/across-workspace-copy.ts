import type { PracticesAcrossWorkspaceTiles, WorkspaceSplit } from "@/api/types.gen";
import { ACTIVITY_RANGE_DEFS, type ActivityRange } from "@/components/activity/activity-range";
import type { FilterOption } from "@/components/common/FilterToggle";
import { statusValues } from "@/components/common/status-def";
import {
	isSettledStanding,
	PRACTICE_GROUP_STANDING_DEFS,
	type PracticeGroupStandingValue,
	type StandingScope,
} from "@/components/practice-vocabulary/practice-group-standing-defs";
import { NONE_YET_SEGMENT } from "@/components/practice-vocabulary/standing-counts";

export type AcrossWorkspaceWindow = PracticesAcrossWorkspaceTiles["window"];

interface WindowDef {
	/** The toggle's label and the section's heading. */
	label: string;
	/** The toggle's label where the row is short of room. */
	shortLabel: string;
	/** The window as the tail of a sentence: "with a standing so far". */
	phrase: string;
}

/** A window that Activity offers too, in Activity's words for it. */
function activityWindow(range: ActivityRange): WindowDef {
	const def = ACTIVITY_RANGE_DEFS[range];
	return { label: def.label, shortLabel: def.shortLabel, phrase: `in ${def.inSentence}` };
}

/**
 * Every window the page reads, shortest first as Apple Health orders its ranges, named as Activity
 * names the same span. A window the server adds fails to compile here rather than falling back
 * unseen.
 */
const WINDOW_DEFS: Record<AcrossWorkspaceWindow, WindowDef> = {
	DAYS_30: activityWindow("30d"),
	DAYS_90: activityWindow("90d"),
	ALL_TIME: { label: "All time", shortLabel: "All time", phrase: "so far" },
};

/** Every window, in the toggle's order, for the route's search schema. */
export const WINDOW_VALUES = statusValues(WINDOW_DEFS);

/** The window toggle's options. */
export const WINDOW_OPTIONS: readonly FilterOption<AcrossWorkspaceWindow>[] = WINDOW_VALUES.map(
	(value) => ({
		value,
		label: WINDOW_DEFS[value].label,
		shortLabel: WINDOW_DEFS[value].shortLabel,
	}),
);

/** The window the page opens on. */
export const DEFAULT_WINDOW: AcrossWorkspaceWindow = "DAYS_30";

/** The window as the tail of a sentence: "with a standing so far". */
export const windowPhrase = (window: AcrossWorkspaceWindow): string => WINDOW_DEFS[window].phrase;

/** The window as the tiles' heading. */
export const windowHeading = (window: AcrossWorkspaceWindow): string => WINDOW_DEFS[window].label;

/** The page's short name: its entry in the sidebar and the first crumb of its levels' path. */
export const ACROSS_THE_WORKSPACE = "Across the workspace";

/**
 * What the page is for, under its title. The page is a view of the workspace, not the reader's
 * profile, so it sends the reader to the profile for their own next step.
 */
export const PAGE_PURPOSE =
	"This page shows where the developers in this workspace stand in each practice group. Your next step is in your Practice profile.";

/**
 * The link from a group's level, and the row link from each of its practices, to the same group or
 * practice in the reader's own Practice profile. One action, so one label.
 */
export const OPEN_IN_YOUR_PROFILE = "Open in your Practice profile";

/**
 * The line under the tiles on when the three tiles read over the range compare, from the fewest
 * other developers the server reads a middle half over. Open feedback reads every developer the
 * page counts, reviewed or not, so the line names its group too. The tiles are the only figures
 * the window changes, so only this line names it.
 */
export function tilesHint({
	minimumOthersForMiddleHalf,
	window,
	developersWithAStandingInWindow,
}: Pick<
	PracticesAcrossWorkspaceTiles,
	"minimumOthersForMiddleHalf" | "window" | "developersWithAStandingInWindow"
>): string {
	// No count where the server held the total back: a small total is a count of its own.
	const of =
		developersWithAStandingInWindow === undefined
			? "the developers with a standing here"
			: `${developerCount(developersWithAStandingInWindow)} with a standing ${windowPhrase(window)}`;
	return `Except for open feedback, the typical range is the middle half of ${of}. Your marker shows you. These tiles compare you when at least ${minimumOthersForMiddleHalf} other developers have a standing in this range. Until then, they show only your own value. Open feedback counts what is open now, for all developers that this page counts.`;
}

/**
 * The line over a table of bars on what a bar counts and when a part is not shown: a part shows
 * from K + 1 developers, the reader counted, so it stands for K others whoever reads it. A bar
 * counts the current standing, the one each developer's Practice profile shows. A practice's bar
 * shows by the same rule as its group's, and only while it singles no one out beside the group's.
 */
export function barsHint(minimumOthers: number, scope: StandingScope): string {
	const rule = `Each bar counts developers by their current standing in the ${scope}, as their Practice profile shows it. You marks your part. A bar shows only if each of its parts holds at least ${minimumOthers} other developers.`;
	return scope === "group"
		? `${rule} If not, the whole bar is held back, so no one can be singled out.`
		: `${rule} The bar must also single no one out beside the group's bar. If not, it is held back.`;
}

/** What the bar and its text alternative need besides the split itself. */
export interface SplitContext {
	/** Whether the reader is inside the counts; without it no part carries the You marker. */
	readerCounted: boolean;
	/**
	 * The workspace's total of developers with a current standing, the reference group every split
	 * is a part of; absent while the server holds it back.
	 */
	developersWithAStanding?: number;
	/** K: the fewest developers other than the reader a shown count stands for. */
	minimumOthers: number;
}

/** "24 developers", "1 developer". */
export function developerCount(count: number): string {
	return `${count} ${count === 1 ? "developer" : "developers"}`;
}

/**
 * What a split held back says under its empty track, the same in every row and for every reason
 * the privacy rule holds one back: a part too small, or a practice too close to its group. More
 * data does not lift the second, so the words promise nothing about later.
 */
export const HELD_BACK = "Held back so no one can be singled out";

/** The reference group a split is a part of: "24 developers with a current standing in this workspace". */
function referenceGroup(context: SplitContext): string {
	const who =
		context.developersWithAStanding === undefined
			? "Developers"
			: developerCount(context.developersWithAStanding);
	return `${who} with a current standing in this workspace`;
}

/**
 * The bar's text alternative: the named reference group, every count, and the part the You marker
 * is on, as the bar shows it. A split held back says only why, as its track does.
 */
export function splitDescription(
	split: WorkspaceSplit,
	yourStanding: PracticeGroupStandingValue,
	context: SplitContext,
): string {
	if (split.shape === "WITHHELD") {
		return `${HELD_BACK}.`;
	}
	// Each part in the registry's own words and the server's order, so the bar's text says what its
	// legend says.
	const parts = split.parts.map(
		(part) => `${part.developers} ${PRACTICE_GROUP_STANDING_DEFS[part.standing].label}`,
	);
	const noneYet = `${split.noneYet ?? 0} ${NONE_YET_SEGMENT.inSentence}`;
	const yourPart = isSettledStanding(yourStanding)
		? PRACTICE_GROUP_STANDING_DEFS[yourStanding].label
		: NONE_YET_SEGMENT.inSentence;
	const marker = context.readerCounted ? ` The You marker is on ${yourPart}.` : "";
	return `${referenceGroup(context)}: ${[...parts, noneYet].join(", ")}.${marker}`;
}
