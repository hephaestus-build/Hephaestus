import type { PracticesAcrossWorkspace, WorkspaceGroupSplit } from "@/api/types.gen";
import {
	PRACTICE_GROUP_STANDING_DEFS,
	type PracticeGroupStandingValue,
} from "@/components/practice-vocabulary/practice-group-standing-defs";

export type AcrossWorkspaceWindow = PracticesAcrossWorkspace["window"];

/** The three standings a reader can expect of themselves; the two silences are not an estimate. */
export const ESTIMATE_STANDINGS = ["DEVELOPING", "MIXED", "STRENGTH"] as const;
export type EstimateStanding = (typeof ESTIMATE_STANDINGS)[number];

/** What the reader answered for one group: a standing they expect, or that they skipped. */
export type Estimate = EstimateStanding | "SKIPPED";

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

export const standingLabel = (standing: PracticeGroupStandingValue): string =>
	PRACTICE_GROUP_STANDING_DEFS[standing].label;

export function isEstimateStanding(
	standing: PracticeGroupStandingValue,
): standing is EstimateStanding {
	return (ESTIMATE_STANDINGS as readonly string[]).includes(standing);
}

/**
 * The sentence under a group's name once its split is shown: the practice group and its named
 * reference group, every developer observed in the window, are the subject, never the reader, and it
 * says whether the group is within reach.
 */
export function reachSentence(
	group: WorkspaceGroupSplit,
	window: AcrossWorkspaceWindow,
	observedDevelopers: number,
): string | undefined {
	const observed = `of the ${observedDevelopers} developers observed here ${windowPhrase(window)}`;
	if (group.shape === "COLLAPSED") {
		return `${group.hasStanding ?? 0} ${observed} have a standing; the split is held back.`;
	}
	if (group.shape !== "SPLIT") {
		return undefined;
	}
	const needs = group.needsAttention ?? 0;
	const mixed = group.mixedFeedback ?? 0;
	const well = group.goingWell ?? 0;
	if (well >= mixed && well >= needs) {
		return `${well} ${observed} are Going well, so it is within reach.`;
	}
	if (mixed >= needs) {
		return `${mixed} ${observed} get Mixed feedback; many find this group hard.`;
	}
	return `${needs} ${observed} need attention here; many find this group hard.`;
}

/**
 * What a withheld group says in place of its split: the workspace's observed total, the one count a
 * withheld group may carry, since a part of it could single out a developer.
 */
export function withheldSentence(
	observedDevelopers: number,
	window: AcrossWorkspaceWindow,
): string {
	const developers = observedDevelopers === 1 ? "developer" : "developers";
	return `${observedDevelopers} ${developers} observed here ${windowPhrase(window)}; the split is held back.`;
}

/**
 * The reader's estimate beside their standing, in their own words, with no judgement and no advice:
 * two values side by side.
 */
export function estimateSentence(estimate: Estimate, standing: PracticeGroupStandingValue): string {
	const actual = standingLabel(standing);
	if (estimate === "SKIPPED") {
		return `You skipped the estimate; your latest reviewed work reads ${actual}.`;
	}
	const expected = standingLabel(estimate);
	if (standing === "NOT_OBSERVED") {
		return `You expected ${expected}; your reviewed work has not touched this group yet, so it reads ${actual}.`;
	}
	if (standing === "NO_OPPORTUNITY") {
		return `You expected ${expected}; your reviewed work touched this group with nothing to report yet, so it reads ${actual}.`;
	}
	return estimate === standing
		? `You expected ${expected}; your latest reviewed work reads ${actual} too.`
		: `You expected ${expected}; your latest reviewed work reads ${actual}.`;
}

/** The bar's text alternative: the named reference group, every count, and the reader's own word. */
export function splitDescription(
	group: WorkspaceGroupSplit,
	window: AcrossWorkspaceWindow,
	readerCounted: boolean,
	observedDevelopers: number,
): string {
	const you = `You: ${standingLabel(group.yourStanding)}`;
	const reference = `${observedDevelopers} developers observed in this workspace ${windowPhrase(window)}`;
	if (group.shape === "COLLAPSED") {
		return `${reference}: ${group.hasStanding ?? 0} have a standing, ${group.noneYet ?? 0} none yet. The split is held back while one standing would cover fewer than five developers other than you. ${you}.`;
	}
	const counted = readerCounted && isEstimateStanding(group.yourStanding);
	const needs = group.needsAttention ?? 0;
	const mixed = group.mixedFeedback ?? 0;
	const well = group.goingWell ?? 0;
	const noneYet = observedDevelopers - needs - mixed - well;
	return `${reference}: ${needs} Needs attention, ${mixed} Mixed feedback, ${well} Going well, ${noneYet} none yet. ${you}${counted ? "" : ", not counted in the split"}.`;
}

/** Practice profile order: what needs attention first, then the silences; the name breaks a tie. */
const STANDING_ORDER: Record<PracticeGroupStandingValue, number> = {
	DEVELOPING: 0,
	MIXED: 1,
	STRENGTH: 2,
	NO_OPPORTUNITY: 3,
	NOT_OBSERVED: 4,
};

/**
 * The rows in catalogue order, alphabetical, while any row still asks: an order by standing would
 * tell the reader their answer before they gave it. Once nothing asks, the practice profile's order.
 */
export function orderGroups(
	groups: readonly WorkspaceGroupSplit[],
	byStanding: boolean,
): WorkspaceGroupSplit[] {
	return [...groups].sort(
		(a, b) =>
			(byStanding ? STANDING_ORDER[a.yourStanding] - STANDING_ORDER[b.yourStanding] : 0) ||
			a.groupName.localeCompare(b.groupName),
	);
}

/** How the reader's estimates compare with their standings, counted and never scored. */
export interface EstimateSummary {
	estimated: number;
	skipped: number;
	same: number;
	different: number;
	nothingToCompare: number;
}

export function summarizeEstimates(
	groups: readonly WorkspaceGroupSplit[],
	estimates: Readonly<Record<string, Estimate | undefined>>,
): EstimateSummary {
	const summary: EstimateSummary = {
		estimated: 0,
		skipped: 0,
		same: 0,
		different: 0,
		nothingToCompare: 0,
	};
	for (const group of groups) {
		const estimate = estimates[group.groupSlug];
		if (estimate === undefined) {
			continue;
		}
		if (estimate === "SKIPPED") {
			summary.skipped += 1;
			continue;
		}
		summary.estimated += 1;
		if (!isEstimateStanding(group.yourStanding)) {
			summary.nothingToCompare += 1;
		} else if (estimate === group.yourStanding) {
			summary.same += 1;
		} else {
			summary.different += 1;
		}
	}
	return summary;
}

/** "1 practice group", "3 practice groups". */
export function groupCount(count: number): string {
	return `${count} practice ${count === 1 ? "group" : "groups"}`;
}
