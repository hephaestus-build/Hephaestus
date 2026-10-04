import { TriangleIcon } from "lucide-react";
import type { ComponentType } from "react";

import { cn } from "cn";
import type { WorkspaceSplit } from "@/api/types.gen";
import { statusValues } from "@/components/common/status-def";
import {
	isSettledStanding,
	PRACTICE_GROUP_STANDING_DEFS,
	type PracticeGroupStandingValue,
} from "@/components/practice-vocabulary/practice-group-standing-defs";
import {
	NEUTRAL_GREY,
	NONE_YET_SEGMENT,
	standingColorClass,
} from "@/components/practice-vocabulary/standing-counts";
import { Skeleton } from "@/components/ui/skeleton";

import {
	HELD_BACK,
	shownSplit,
	SPLIT_HELD_BACK,
	splitDescription,
	splitTotalText,
} from "./across-workspace-copy";

export interface WorkspaceSplitBarProps {
	split: WorkspaceSplit;
	/** The part the You marker is on, while the reader is counted and the split shows its parts. */
	yourStanding?: PracticeGroupStandingValue;
	/** Whether the reader is one of the developers the split counts. */
	readerCounted: boolean;
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

const NONE_YET_PART: PartDef = { key: "none", ...NONE_YET_SEGMENT };

/** In the server's order: the verdicts in registry order, then none yet. */
const SPLIT_PARTS: readonly PartDef[] = [
	...statusValues(PRACTICE_GROUP_STANDING_DEFS).filter(isSettledStanding).map(standingPart),
	NONE_YET_PART,
];

/** Dashed, so an empty track is never read as a part of none yet. */
const HELD_BACK_TRACK = cn("h-2 rounded-sm border border-dashed border-current", NEUTRAL_GREY);

/** In no verdict's colour, so a total cannot be read as one part. */
const TOTAL_ONLY_BAR = cn("h-2 rounded-sm bg-current", NEUTRAL_GREY);

function YouMarker() {
	return (
		<>
			<span className="text-xs leading-3 font-semibold">You</span>
			<TriangleIcon aria-hidden className="size-2.5 rotate-180 fill-current" />
		</>
	);
}

/**
 * How the developers with a current standing split across one practice group or practice, as one
 * bar counted in developers. Each part carries its count and its standing's icon under it, so no
 * part rests on colour alone (WCAG 2.2 SC 1.4.1). A split shown only as its total is one neutral
 * bar and marks no one. A split held back whole is a dashed track with its reason. All three take
 * the same width and height, so rows of mixed shapes line up.
 */
export function WorkspaceSplitBar({
	split: wire,
	yourStanding,
	readerCounted,
}: WorkspaceSplitBarProps) {
	const split = shownSplit(wire);
	if (split === undefined) {
		return (
			<div className="flex w-full min-w-0 flex-col gap-1">
				<span aria-hidden className={cn("mt-5 w-full", HELD_BACK_TRACK)} />
				<p className="text-xs text-muted-foreground">{HELD_BACK}.</p>
			</div>
		);
	}
	const description = splitDescription(wire, yourStanding, readerCounted);
	const total = (
		<p aria-hidden className="text-right text-xs text-muted-foreground tabular-nums">
			{splitTotalText(split)}
		</p>
	);
	if (split.shape === "TOTAL_ONLY") {
		return (
			<div className="flex w-full min-w-0 flex-col gap-1">
				<div role="img" aria-label={description} className="flex w-full min-w-0 flex-col gap-0.5">
					{/* The marker's row, empty, so the bar sits where a split's bar sits. */}
					<span aria-hidden className="h-5" />
					<span aria-hidden className={cn("w-full", TOTAL_ONLY_BAR)} />
					<span aria-hidden className="text-xs text-muted-foreground">
						{SPLIT_HELD_BACK}
					</span>
				</div>
				{total}
			</div>
		);
	}
	const parts = [
		...split.parts.map((part) => ({
			def: standingPart(part.standing),
			count: part.developers,
			isYours: readerCounted && part.standing === yourStanding,
		})),
		{
			def: NONE_YET_PART,
			count: split.noneYet,
			isYours: readerCounted && yourStanding !== undefined && !isSettledStanding(yourStanding),
		},
	];
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
							<span className="flex h-5 flex-col items-center justify-end text-mentor">
								{isYours && <YouMarker />}
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
			{total}
		</div>
	);
}

/** The bar's lines while it loads: the marker's row, the bar, the counts, then the total. */
export function WorkspaceSplitBarSkeleton() {
	return (
		<div aria-hidden className="flex w-full min-w-0 flex-col gap-1">
			<div className="flex flex-col gap-0.5">
				<span className="h-5" />
				<Skeleton className="h-2 w-full rounded-sm" />
				<Skeleton className="h-4 w-full" />
			</div>
			<Skeleton className="ml-auto h-4 w-24" />
		</div>
	);
}

/** A split at the width a level header's aside gives it. */
export function LevelSplit({
	state,
}: {
	state: { status: "loading" } | ({ status: "ready" } & WorkspaceSplitBarProps);
}) {
	return (
		<div className="flex w-full sm:w-88">
			{state.status === "ready" ? <WorkspaceSplitBar {...state} /> : <WorkspaceSplitBarSkeleton />}
		</div>
	);
}

/**
 * Once above each table of bars. A held-back track says what it is under itself, so the legend
 * leaves it out.
 */
export function SplitLegend() {
	return (
		<ul
			aria-label="What the bars show"
			className="flex flex-wrap items-center gap-x-4 gap-y-1.5 text-xs text-muted-foreground"
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
			<li className="inline-flex items-center gap-1 text-mentor">
				<YouMarker />
				<span className="text-muted-foreground">marks your part</span>
			</li>
			<li className="inline-flex items-center gap-1.5">
				<span aria-hidden className={cn("w-4", TOTAL_ONLY_BAR)} />
				{SPLIT_HELD_BACK}
			</li>
		</ul>
	);
}
