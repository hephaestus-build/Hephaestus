import { CircleDashedIcon } from "lucide-react";

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
import { NEUTRAL_GREY } from "@/components/practice-vocabulary/standing-counts";

import type { InfoGroup, InfoLine } from "@/components/common/InfoTip";

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

/** The page's one visible line under its title; {@link PAGE_INTRO} says the rest behind an (i). */
export const PAGE_INTRO_SHORT = "Where the developers in this workspace stand. Not a grade.";

/** Behind the (i) beside {@link PAGE_INTRO_SHORT}. */
export const PAGE_INTRO: readonly InfoLine[] = [
	{
		term: "What it shows:",
		text: "where the developers in this workspace stand in each practice group, each counted once.",
	},
	{
		term: "Not a grade:",
		text: "each standing comes from AI review of their work and can be wrong.",
	},
	{ term: "Your next step:", text: "it is in your Practice profile." },
];

/** Keep completed queries fresh across window changes and brief focus changes. */
export const ACROSS_WORKSPACE_STALE_MS = 5 * 60 * 1000;

/** The overview failed: the bars and the practice groups' levels have nothing to show. */
export const GROUPS_LOAD_ERROR = "We could not load the practice groups";

/** The window's figures failed; the bars, which read no window, stay. */
export const FIGURES_LOAD_ERROR = "We could not load your figures";

/** Digits always: a count of developers sits beside other figures, never in running prose. */
const developers = (n: number) => count(n, "developer", "developers", true);

export const tileCohortText = (tiles: PracticesAcrossWorkspaceTiles) =>
	`${developers(tiles.developersWithAStandingInWindow)} with a standing`;

/**
 * The tiles' (i), in four groups that do not overlap: what the tile draws, how to read it, when the
 * band is missing, and what the open feedback counts. The same for every window and every
 * workspace, so it never carries a count or a window of its own.
 */
export const FIGURES_PLAIN: readonly InfoGroup[] = [
	{
		heading: "What you see",
		lines: [
			{
				term: "Grey band:",
				text: "the typical range here, the middle half of the developers with a standing.",
				swatchClass: NEUTRAL_GREY,
			},
			{ term: "Blue marker:", text: "your own value.", swatchClass: "text-mentor" },
		],
	},
	{
		heading: "How to read it",
		lines: [
			{
				term: "Left of the band:",
				text: "lower than for most developers.",
				second: { term: "Right:", text: "higher." },
			},
			{
				term: "Better or worse:",
				text: "depends on the figure. Fewer practices needing attention is better; fewer going well is not.",
			},
		],
	},
	{
		heading: "When the band is missing",
		lines: [{ term: "No band:", text: "no developer has a standing yet." }],
	},
	{
		heading: "What counts",
		lines: [
			{ term: "Open feedback:", text: "what is open now, for every developer counted here." },
		],
	},
];

/** The part of a split for developers with a standing elsewhere on the page but no verdict here. */
export const NONE_YET_SEGMENT = {
	label: "None yet",
	inSentence: "none yet",
	icon: CircleDashedIcon,
	colorClass: NEUTRAL_GREY,
} as const;

/**
 * The plain reading of a bar's colours, first in the legend's (i). Grey is no standing, never "okay".
 */
export const NONE_YET_PLAIN: InfoLine = {
	term: "Grey, None yet:",
	text: "no standing yet. No review has judged this in their work, or there was nothing to judge. Grey does not mean okay.",
};

/** The lines in the legend's (i) after the colours, in the words of `docs/user/practice-profile.mdx` § The bars. */
export function barsHint(scope: StandingScope): string {
	return [
		`Each bar counts developers by their current standing in the ${scope}, as their Practice profile shows it.`,
		"Each developer counts once, however much of their work was reviewed.",
		"The You bracket spans your part: you are somewhere in it, not at one point.",
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
 * server's order, and the part the You bracket spans, so the bar's text says what its legend says.
 */
export function splitDescription(
	split: WorkspaceSplit,
	yourStanding: PracticeGroupStandingValue | undefined,
): string {
	if (split.developers === 0) {
		return `${NOBODY_YET}.`;
	}
	const whole = `${splitTotalText(split)} in this workspace`;
	const parts = split.parts.map(
		(part) => `${part.developers} ${PRACTICE_GROUP_STANDING_DEFS[part.standing].label}`,
	);
	const noneYet = `${split.noneYet} ${NONE_YET_SEGMENT.inSentence}`;
	const counts = `${whole}: ${[...parts, noneYet].join(", ")}.`;
	if (yourStanding === undefined) {
		return counts;
	}
	const yourPart = isSettledStanding(yourStanding)
		? `the ${PRACTICE_GROUP_STANDING_DEFS[yourStanding].label} part`
		: "the part with no standing yet";
	return `${counts} You are in ${yourPart}.`;
}
