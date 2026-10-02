import type { PracticesAcrossWorkspace, WorkspaceSplit } from "@/api/types.gen";
import {
	PRACTICE_GROUP_STANDING_DEFS,
	type PracticeGroupStandingValue,
} from "@/components/practice-vocabulary/practice-group-standing-defs";

export type AcrossWorkspaceWindow = PracticesAcrossWorkspace["window"];

/** The window toggle's options, the term last and the default, as Apple Health orders its ranges. */
export const WINDOW_OPTIONS = [
	{ value: "DAYS_30", label: "30 days" },
	{ value: "DAYS_90", label: "90 days" },
	{ value: "TERM", label: "Term" },
] as const satisfies readonly { value: AcrossWorkspaceWindow; label: string }[];

const WINDOW_PHRASES: Record<AcrossWorkspaceWindow, string> = {
	TERM: "this term",
	DAYS_30: "in the last 30 days",
	DAYS_90: "in the last 90 days",
};

const WINDOW_HEADINGS: Record<AcrossWorkspaceWindow, string> = {
	TERM: "This term",
	DAYS_30: "The last 30 days",
	DAYS_90: "The last 90 days",
};

/** The window as the tail of a sentence: "observed here this term". */
export const windowPhrase = (window: AcrossWorkspaceWindow): string => WINDOW_PHRASES[window];

/** The window as a section heading. */
export const windowHeading = (window: AcrossWorkspaceWindow): string => WINDOW_HEADINGS[window];

/** The one sentence under the page title that says what every course figure on the page is. */
export const QUANTILE_NOTE =
	"Course figures show the middle half of developers, from the 25th to the 75th percentile.";

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
	/** The workspace's observed total, the reference group every split is a part of. */
	observedDevelopers: number;
	/** K: the fewest developers other than the reader a shown count stands for. */
	minimumOthers: number;
}

/**
 * Whether a split held back still names the observed total: only while the developers other than
 * the reader reach K, since a total under it would be a small count of its own.
 */
export function showsTotal(context: SplitContext): boolean {
	return context.observedDevelopers - (context.readerCounted ? 1 : 0) >= context.minimumOthers;
}

/** "24 developers", "1 developer". */
export function developerCount(count: number): string {
	return `${count} ${count === 1 ? "developer" : "developers"}`;
}

/**
 * What a split held back says in place of its bar: the observed total, the one count it may carry,
 * or that too few developers are observed for even that.
 */
export function heldBackSentence(context: SplitContext): string {
	return showsTotal(context)
		? `Split held back: ${developerCount(context.observedDevelopers)} observed ${windowPhrase(context.window)}.`
		: "Too few developers observed to compare yet.";
}

/** The bar's text alternative: the named reference group, every count, and the reader's own word. */
export function splitDescription(
	split: WorkspaceSplit,
	yourStanding: PracticeGroupStandingValue,
	context: SplitContext,
): string {
	const you = `You: ${standingLabel(yourStanding)}`;
	const reference = `${developerCount(context.observedDevelopers)} observed in this workspace ${windowPhrase(context.window)}`;
	if (split.shape === "WITHHELD") {
		return `${heldBackSentence(context)} ${you}.`;
	}
	if (split.shape === "COLLAPSED") {
		return `${reference}: ${split.hasStanding ?? 0} have a standing, ${split.noneYet ?? 0} none yet. The split is held back while one standing would cover fewer than ${context.minimumOthers} developers other than you. ${you}.`;
	}
	const needs = split.needsAttention ?? 0;
	const mixed = split.mixedFeedback ?? 0;
	const well = split.goingWell ?? 0;
	const noneYet = context.observedDevelopers - needs - mixed - well;
	const counted = context.readerCounted && isSplitStanding(yourStanding);
	return `${reference}: ${needs} Needs attention, ${mixed} Mixed feedback, ${well} Going well, ${noneYet} none yet. ${you}${counted ? "" : ", not counted in the split"}.`;
}
