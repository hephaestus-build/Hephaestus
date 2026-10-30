// `role="img"`, not a Base UI `Meter`: a meter announces one value in a range, and a split is four
// counts of one whole. CSS, not Recharts (`ui/chart.tsx`): one stacked row needs no axis or scale.

import type { ComponentType } from "react";

import { cn } from "cn";
import type { WorkspaceSplit } from "@/api/types.gen";
import { type BadgeVariant, statusValues } from "@/components/common/status-def";
import {
	isSettledStanding,
	PRACTICE_GROUP_STANDING_DEFS,
	type PracticeGroupStandingValue,
	type StandingScope,
} from "@/components/practice-vocabulary/practice-group-standing-defs";
import { NEUTRAL_GREY, standingColorClass } from "@/components/practice-vocabulary/standing-counts";
import { SUMMARY_FRAME } from "@/components/practice-vocabulary/StandingSummaryBox";
import { Skeleton } from "@/components/ui/skeleton";

import { type InfoLine, InfoLines, InfoTip } from "@/components/common/InfoTip";
import {
	barsHint,
	NOBODY_YET,
	NONE_YET_PLAIN,
	NONE_YET_SEGMENT,
	splitDescription,
	splitTotalText,
} from "./across-workspace-copy";

export interface WorkspaceSplitBarProps {
	split: WorkspaceSplit;
	/**
	 * The part the You bracket spans. The server sends it only on a split that counts the reader, so
	 * its absence is the unmarked bar.
	 */
	yourStanding?: PracticeGroupStandingValue;
}

interface PartDef {
	key: string;
	label: string;
	icon: ComponentType<{ className?: string }>;
	/** A text colour: the bar paints with `currentColor`, so a part and its icon share it. */
	colorClass: string;
}

function standingPart(standing: PracticeGroupStandingValue): PartDef {
	const def = PRACTICE_GROUP_STANDING_DEFS[standing];
	return {
		key: standing,
		label: def.label,
		icon: def.icon,
		colorClass: standingColorClass(standing),
	};
}

/** The colour each verdict wears, in words, so the legend's (i) does not rest on colour alone. */
const COLOUR_WORD: Partial<Record<BadgeVariant, string>> = {
	destructive: "Red, ",
	warning: "Orange, ",
	success: "Green, ",
};

const NONE_YET_PART: PartDef = { key: "none", ...NONE_YET_SEGMENT };

/** In the server's order: the verdicts in registry order, then none yet. */
const SPLIT_PARTS: readonly PartDef[] = [
	...statusValues(PRACTICE_GROUP_STANDING_DEFS).filter(isSettledStanding).map(standingPart),
	NONE_YET_PART,
];

/** Dashed, so an empty track is never read as a part of none yet. */
const EMPTY_TRACK = cn("h-2 rounded-sm border border-dashed border-current", NEUTRAL_GREY);

/**
 * A bracket open toward the bar, as wide as the reader's part. Not a point: the bar knows only the
 * part the reader is in, never a place within it, and a pointer reads as one.
 */
const YOU_BRACKET = "h-1.5 rounded-t-sm border-x-2 border-t-2 border-current";

function YouBracket() {
	return (
		<>
			<span className="text-xs leading-3 font-semibold">You</span>
			<span aria-hidden data-slot="you-bracket" className={cn("w-full", YOU_BRACKET)} />
		</>
	);
}

/**
 * How developers split across one practice group or practice, including none yet, as one
 * bar counted in developers, each developer once. Each part carries its count and its standing's
 * icon under it, so no part rests on colour alone (WCAG 2.2 SC 1.4.1). A split that counts nobody
 * is a dashed track that says so, at the same width, so the rows line up.
 */
export function WorkspaceSplitBar({ split, yourStanding }: WorkspaceSplitBarProps) {
	if (split.developers === 0) {
		return (
			<div className="flex w-full min-w-0 flex-col gap-1">
				<EmptyTrack />
				<p className="text-xs text-muted-foreground">{NOBODY_YET}.</p>
			</div>
		);
	}
	const description = splitDescription(split, yourStanding);
	const parts = [
		...split.parts.map((part) => ({
			def: standingPart(part.standing),
			count: part.developers,
			isYours: part.standing === yourStanding,
		})),
		{
			def: NONE_YET_PART,
			count: split.noneYet,
			isYours: yourStanding !== undefined && !isSettledStanding(yourStanding),
		},
		// `min-w-10` would draw a part of nobody as a coloured block; the text alternative names it.
	].filter(({ count }) => count > 0);
	return (
		<div className="flex w-full min-w-0 flex-col gap-1">
			<div role="img" aria-label={description} className="flex w-full min-w-0 gap-0.5">
				{parts.map(({ def, count, isYours }) => {
					const Icon = def.icon;
					return (
						<div
							key={def.key}
							aria-hidden
							title={`${def.label}: ${count}`}
							// Each count is centred in its own part's column, and `min-w-10` keeps room for it, so
							// two narrow parts never print their counts over each other.
							className="flex min-w-10 grow-(--count) basis-0 flex-col items-center gap-0.5"
							style={{ "--count": count }}
						>
							{/* `pb-0.5` keeps the bracket's corners off the part it spans. */}
							<span className="flex h-5.5 w-full flex-col items-center justify-end gap-0.5 pb-0.5 text-highlight">
								{isYours && <YouBracket />}
							</span>
							<span className={cn("h-2 w-full rounded-sm bg-current", def.colorClass)} />
							<span className="flex w-full items-center justify-center gap-1 text-xs text-muted-foreground tabular-nums">
								<Icon className={cn("size-3 shrink-0", def.colorClass)} />
								{count}
							</span>
						</div>
					);
				})}
			</div>
			{/* On its own line, so the counts stay centred under their parts. */}
			<p aria-hidden className="text-right text-xs text-muted-foreground tabular-nums">
				{splitTotalText(split)}
			</p>
		</div>
	);
}

/**
 * The empty track of a split that counts nobody, at a bar's height and place. A table whose every
 * split counts nobody draws it alone and says why once.
 */
export function EmptyTrack() {
	return <div aria-hidden className={cn("mt-5.5 w-full", EMPTY_TRACK)} />;
}

/** Unequal parts, so the loading bar reads as a split rather than one block. */
const SKELETON_PARTS = [5, 4, 6, 3] as const;

/**
 * The bar's lines while it loads, at the loaded bar's heights: the bracket's row, the parts with a
 * count under each, then the total.
 */
export function WorkspaceSplitBarSkeleton() {
	return (
		<div aria-hidden className="flex w-full min-w-0 flex-col gap-1">
			<div className="flex w-full min-w-0 gap-0.5">
				{SKELETON_PARTS.map((grow) => (
					<div
						key={grow}
						className="flex min-w-10 grow-(--count) basis-0 flex-col items-center gap-0.5"
						style={{ "--count": grow }}
					>
						<span className="h-5.5" />
						<Skeleton className="h-2 w-full rounded-sm" />
						<span className="flex h-4 items-center">
							<Skeleton className="h-3 w-7" />
						</span>
					</div>
				))}
			</div>
			<span className="flex h-4 items-center justify-end">
				<Skeleton className="h-3 w-24" />
			</span>
		</div>
	);
}

/**
 * A split at the width a level header's aside gives it, in the frame a practice group's summary
 * wears on the Practice profile.
 */
export function LevelSplit({
	state,
}: {
	state: { status: "loading" } | ({ status: "ready" } & WorkspaceSplitBarProps);
}) {
	return (
		<div className={cn("flex w-full sm:w-96", SUMMARY_FRAME)}>
			{state.status === "ready" ? <WorkspaceSplitBar {...state} /> : <WorkspaceSplitBarSkeleton />}
		</div>
	);
}

/**
 * A verdict's colour and words, as the legend's (i) reads them out: "Red, Needs attention: …", after
 * a sample of the colour, then grey and how the bars count.
 */
const verdictLines = (scope: StandingScope): readonly InfoLine[] => [
	...statusValues(PRACTICE_GROUP_STANDING_DEFS)
		.filter(isSettledStanding)
		.map((standing) => {
			const def = PRACTICE_GROUP_STANDING_DEFS[standing];
			return {
				term: `${COLOUR_WORD[def.badgeVariant] ?? ""}${def.label}:`,
				text: def.description,
				swatchClass: standingColorClass(standing),
			};
		}),
	{ ...NONE_YET_PLAIN, swatchClass: NONE_YET_SEGMENT.colorClass },
	{ term: "How the bars count:", text: barsHint(scope) },
];

/**
 * Once above each table of bars, with an (i) at its end that says what each colour means, grey
 * included, and how the bars count. An empty track says what it is under itself, so the legend
 * leaves it out.
 */
export function SplitLegend({ scope }: { scope: StandingScope }) {
	return (
		<div className="flex items-start gap-2">
			<SplitLegendItems />
			<InfoTip label="About the bars" className="my-0 -mt-1">
				<InfoLines lines={verdictLines(scope)} />
			</InfoTip>
		</div>
	);
}

function SplitLegendItems() {
	return (
		<ul
			aria-label="What the bars show"
			className="flex min-w-0 flex-wrap items-center gap-x-4 gap-y-1.5 text-xs text-muted-foreground"
		>
			{SPLIT_PARTS.map((part) => {
				const Icon = part.icon;
				return (
					<li key={part.key} className="inline-flex items-center gap-1.5">
						<span aria-hidden className={cn("h-2 w-4 rounded-sm bg-current", part.colorClass)} />
						<Icon aria-hidden className={cn("size-3 shrink-0", part.colorClass)} />
						{part.label}
					</li>
				);
			})}
			<li className="inline-flex items-center gap-1.5 text-highlight">
				<span aria-hidden className={cn("mt-1 w-4", YOU_BRACKET)} />
				<span className="font-semibold">You</span>
				<span className="text-muted-foreground">spans your part</span>
			</li>
		</ul>
	);
}
