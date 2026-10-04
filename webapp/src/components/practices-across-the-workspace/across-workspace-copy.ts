import type { PracticesAcrossWorkspace, WorkspaceSplit } from "@/api/types.gen";
import type { FilterOption } from "@/components/common/FilterToggle";
import { statusValues } from "@/components/common/status-def";
import {
	PRACTICE_GROUP_STANDING_DEFS,
	type PracticeGroupStandingValue,
} from "@/components/practice-vocabulary/practice-group-standing-defs";

export type AcrossWorkspaceWindow = PracticesAcrossWorkspace["window"];

interface WindowDef {
	/** The toggle's label and the section's heading. */
	label: string;
	/** The toggle's label where the row is short of room. */
	shortLabel: string;
	/** The window as the tail of a sentence: "with a standing so far". */
	phrase: string;
}

/**
 * Every window the page reads, the one home of its words, shortest first as Apple Health orders
 * its ranges. A window the server adds fails to compile here rather than falling back unseen.
 */
const WINDOW_DEFS: Record<AcrossWorkspaceWindow, WindowDef> = {
	DAYS_30: { label: "Last 30 days", shortLabel: "30 days", phrase: "in the last 30 days" },
	DAYS_90: { label: "Last 90 days", shortLabel: "90 days", phrase: "in the last 90 days" },
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

/** The window as a section heading. */
export const windowHeading = (window: AcrossWorkspaceWindow): string => WINDOW_DEFS[window].label;

/**
 * The span the Practice profile reads a standing over, its look-back of 90 days: named on its levels
 * where they open over a window that may count another span, so two standings never meet unnamed.
 */
export const PROFILE_SPAN = windowHeading("DAYS_90");

/** What the page is for and how a standing moves, under its title. */
export const PAGE_PURPOSE =
	"See where your practices stand among the developers in this workspace. Use it to choose what to work on next. Your next pieces of reviewed work move your standings. To see your next step, open a group, then your own group.";

/**
 * The line under the tiles on when the three tiles read over the range compare: a middle half shows
 * from twice K other developers, so neither quarter outside it can be one developer's value. Open
 * feedback reads every developer the page counts, reviewed or not, so its own note names its group.
 */
export function tilesHint(
	minimumOthers: number,
	window: AcrossWorkspaceWindow,
	developersWithAStanding?: number,
): string {
	// No count where the server held the total back: a small total is a count of its own.
	const of =
		developersWithAStanding === undefined
			? "the developers with a standing here"
			: `${developerCount(developersWithAStanding)} with a standing ${windowPhrase(window)}`;
	return `Except for open feedback, the typical range is the middle half of ${of}. Your marker shows you. These tiles compare you when at least ${2 * minimumOthers} other developers have a standing in this range. Until then, they show only your own value.`;
}

/**
 * The line under All practice groups on what a bar counts and when a part is not shown: a part
 * shows from K + 1 developers, the reader counted, so it stands for K others whoever reads it.
 */
export function groupsHint(minimumOthers: number): string {
	return `Each bar counts developers by their standing in the group. You marks your standing. A bar shows only if each of its parts holds at least ${minimumOthers} other developers. If not, the whole bar is held back, so no one can be singled out.`;
}

/**
 * The line over a group's practices on when their bars show: by the same rule as a group's, and
 * only while a practice's bar set against its group's singles no one out.
 */
export function practicesHint(minimumOthers: number): string {
	return `Each bar counts developers by their standing in the practice. You marks your standing. A bar shows only if each of its parts holds at least ${minimumOthers} other developers. The bar must also single no one out beside the group's bar. If not, it is held back.`;
}

export const standingLabel = (standing: PracticeGroupStandingValue): string =>
	PRACTICE_GROUP_STANDING_DEFS[standing].label;

/** The three standings a split counts, in the order the profile lists them. */
export const SPLIT_STANDINGS = ["DEVELOPING", "MIXED", "STRENGTH"] as const;
export type SplitStanding = (typeof SPLIT_STANDINGS)[number];

/** The field of a split that counts each standing. */
export const SPLIT_FIELDS = {
	DEVELOPING: "needsAttention",
	MIXED: "mixedFeedback",
	STRENGTH: "goingWell",
} as const satisfies Record<SplitStanding, keyof WorkspaceSplit>;

export function isSplitStanding(standing: PracticeGroupStandingValue): standing is SplitStanding {
	return (SPLIT_STANDINGS as readonly string[]).includes(standing);
}

/** The part of a split that counts the developers with no standing yet, whatever the reason. */
export const NONE_YET = "None yet";

/**
 * The reader's own standing in the split's words: one of its standings, or none yet with the
 * profile's own reason after it, so the word matches the part the legend names.
 */
export function yourStandingWord(standing: PracticeGroupStandingValue): string {
	return isSplitStanding(standing)
		? standingLabel(standing)
		: `${NONE_YET} (${standingLabel(standing)})`;
}

/** What the bar and its text alternative need besides the split itself. */
export interface SplitContext {
	window: AcrossWorkspaceWindow;
	/** Whether the reader is inside the counts; without it no part carries the You marker. */
	readerCounted: boolean;
	/**
	 * The workspace's total of developers with a standing, the reference group every split is a part
	 * of; absent while the server holds it back.
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

/** The reference group a split is a part of: "24 developers with a standing in this workspace so far". */
function referenceGroup(context: SplitContext): string {
	const who =
		context.developersWithAStanding === undefined
			? "Developers"
			: developerCount(context.developersWithAStanding);
	return `${who} with a standing in this workspace ${windowPhrase(context.window)}`;
}

/** The bar's text alternative: the named reference group, every count, and the reader's own word. */
export function splitDescription(
	split: WorkspaceSplit,
	yourStanding: PracticeGroupStandingValue,
	context: SplitContext,
): string {
	const you = `You: ${yourStandingWord(yourStanding)}`;
	const reference = referenceGroup(context);
	if (split.shape === "WITHHELD") {
		return `${HELD_BACK}. ${you}.`;
	}
	// Each standing in the registry's own words, so the bar's text says what its legend says.
	const standings = SPLIT_STANDINGS.map(
		(standing) => `${split[SPLIT_FIELDS[standing]] ?? 0} ${standingLabel(standing)}`,
	);
	const noneYet = split.noneYet ?? 0;
	const counted = context.readerCounted;
	return `${reference}: ${standings.join(", ")}, ${noneYet} none yet. ${you}${counted ? "" : ", not counted in the split"}.`;
}
