import type { PracticesAcrossWorkspace, WorkspaceSplit } from "@/api/types.gen";
import type { FilterOption } from "@/components/common/FilterToggle";
import {
	PRACTICE_GROUP_STANDING_DEFS,
	type PracticeGroupStandingValue,
} from "@/components/practice-vocabulary/practice-group-standing-defs";

export type AcrossWorkspaceWindow = PracticesAcrossWorkspace["window"];

/** The window toggle's options, shortest first, as Apple Health orders its ranges. */
export const WINDOW_OPTIONS = [
	{ value: "DAYS_30", label: "Last 30 days", shortLabel: "30 days" },
	{ value: "DAYS_90", label: "Last 90 days", shortLabel: "90 days" },
	{ value: "ALL_TIME", label: "All time", shortLabel: "All time" },
] as const satisfies readonly FilterOption<AcrossWorkspaceWindow>[];

/** The window the page opens on. */
export const DEFAULT_WINDOW: AcrossWorkspaceWindow = "DAYS_30";

const WINDOW_PHRASES: Record<AcrossWorkspaceWindow, string> = {
	ALL_TIME: "so far",
	DAYS_30: "in the last 30 days",
	DAYS_90: "in the last 90 days",
};

const WINDOW_HEADINGS: Record<AcrossWorkspaceWindow, string> = {
	ALL_TIME: "All time",
	DAYS_30: "Last 30 days",
	DAYS_90: "Last 90 days",
};

/** The window as the tail of a sentence: "observed here so far". */
export const windowPhrase = (window: AcrossWorkspaceWindow): string => WINDOW_PHRASES[window];

/** The window as a section heading. */
export const windowHeading = (window: AcrossWorkspaceWindow): string => WINDOW_HEADINGS[window];

/** What the page is for and how a standing moves, under its title. */
export const PAGE_PURPOSE =
	"See where your practices stand among the developers in this workspace, so you can choose what to work on next. Standings move with your next pieces of reviewed work; open a group to see your next step.";

/**
 * The line under the tiles on when a tile compares: a middle half shows from twice K other
 * developers, so neither quarter outside it can be one developer's value.
 */
export function tilesHint(
	minimumOthers: number,
	window: AcrossWorkspaceWindow,
	observedDevelopers?: number,
): string {
	// No count where the server held the total back: a small total is a count of its own.
	const of =
		observedDevelopers === undefined
			? "the developers here"
			: `${developerCount(observedDevelopers)} observed ${windowPhrase(window)}`;
	return `The typical range is the middle half of ${of}; your marker shows you. A tile compares you once at least ${2 * minimumOthers} other developers have reviewed work in this window; until then it shows only your own value.`;
}

/**
 * The line under All practice groups on what a bar counts and when a part is not shown: a part
 * shows from K + 1 developers, the reader counted, so it stands for K others whoever reads it.
 */
export function groupsHint(minimumOthers: number): string {
	return `Each bar counts developers by their standing in the group, and You marks yours. A bar shows only when each of its parts holds at least ${minimumOthers} other developers; otherwise the whole bar is held back, so no one can be singled out.`;
}

export const standingLabel = (standing: PracticeGroupStandingValue): string =>
	PRACTICE_GROUP_STANDING_DEFS[standing].label;

/** The three standings a split counts, in the order the profile lists them. */
export const SPLIT_STANDINGS = ["DEVELOPING", "MIXED", "STRENGTH"] as const;
export type SplitStanding = (typeof SPLIT_STANDINGS)[number];

export function isSplitStanding(standing: PracticeGroupStandingValue): standing is SplitStanding {
	return (SPLIT_STANDINGS as readonly string[]).includes(standing);
}

/** What the bar and its text alternative need besides the split itself. */
export interface SplitContext {
	window: AcrossWorkspaceWindow;
	/** Whether the reader is inside the counts; without it no part carries the You marker. */
	readerCounted: boolean;
	/**
	 * The workspace's observed total, the reference group every split is a part of; absent while the
	 * server holds it back.
	 */
	observedDevelopers?: number;
	/** K: the fewest developers other than the reader a shown count stands for. */
	minimumOthers: number;
}

/** "24 developers", "1 developer". */
export function developerCount(count: number): string {
	return `${count} ${count === 1 ? "developer" : "developers"}`;
}

/**
 * What a split held back says under its empty track, the same in every row: the observed total is
 * said once, above the table, rather than again on each row.
 */
export const HELD_BACK = "Held back: too few developers to compare yet";

/** The reference group a split is a part of: "24 developers observed in this workspace so far". */
function referenceGroup(context: SplitContext): string {
	const observed =
		context.observedDevelopers === undefined
			? "Developers"
			: developerCount(context.observedDevelopers);
	return `${observed} observed in this workspace ${windowPhrase(context.window)}`;
}

/** The bar's text alternative: the named reference group, every count, and the reader's own word. */
export function splitDescription(
	split: WorkspaceSplit,
	yourStanding: PracticeGroupStandingValue,
	context: SplitContext,
): string {
	const you = `You: ${standingLabel(yourStanding)}`;
	const reference = referenceGroup(context);
	if (split.shape === "WITHHELD") {
		return `${HELD_BACK}. ${you}.`;
	}
	const needs = split.needsAttention ?? 0;
	const mixed = split.mixedFeedback ?? 0;
	const well = split.goingWell ?? 0;
	const noneYet = split.noneYet === undefined ? "" : `, ${split.noneYet} none yet`;
	const counted = context.readerCounted;
	return `${reference}: ${needs} Needs attention, ${mixed} Mixed feedback, ${well} Going well${noneYet}. ${you}${counted ? "" : ", not counted in the split"}.`;
}
