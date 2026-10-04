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
	developerCount,
	HELD_BACK,
	type SplitContext,
	splitDescription,
} from "./across-workspace-copy";

/** One group's or one practice's split, and where the reader is on it. */
interface SplitOfRow {
	split: WorkspaceSplit;
	/**
	 * The reader's current standing in the group or the practice: the part the You marker is on,
	 * while the reader is counted and the split shows.
	 */
	yourStanding: PracticeGroupStandingValue;
}

export interface WorkspaceSplitBarProps extends SplitContext, SplitOfRow {}

/** One part a split can show: its words, its icon and the colour its icon and its piece of bar wear. */
interface PartDef {
	key: string;
	label: string;
	icon: ComponentType<{ className?: string }>;
	/** A text colour; the bar paints with `currentColor`, so the part and its icon share it. */
	colorClass: string;
}

/** A verdict's part, in the registry's words, icon and tone, never written here. */
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

/**
 * Every part a split can show, in the order the server sends them: the verdicts in registry order,
 * then none yet. The legend lists these; a bar draws the wire's parts with the same definitions.
 */
const SPLIT_PARTS: readonly PartDef[] = [
	...statusValues(PRACTICE_GROUP_STANDING_DEFS).filter(isSettledStanding).map(standingPart),
	NONE_YET_PART,
];

/** The empty track of a split held back, dashed so it is never read as a part of none yet. */
const HELD_BACK_TRACK = cn("h-2 rounded-sm border border-dashed border-current", NEUTRAL_GREY);

/**
 * The reader's place on a bar: the word over a downward pointer. The page's one accent, as the
 * palette in `webapp/AGENTS.md` allows it.
 */
function YouMarker() {
	return (
		<>
			<span className="text-xs leading-3 font-semibold">You</span>
			<TriangleIcon aria-hidden className="size-2.5 rotate-180 fill-current" />
		</>
	);
}

/**
 * How the developers with a current standing split across one practice group or one practice, as
 * one segmented bar counted in developers: Needs attention, Mixed feedback, Going well and none
 * yet. Each part carries its count under it, with the standing's icon so the parts never rest on
 * colour alone, and the You marker sits over the reader's own part. A split held back is a dashed
 * track of the same width with its reason, and nothing more.
 */
export function WorkspaceSplitBar({ split, yourStanding, ...context }: WorkspaceSplitBarProps) {
	if (split.shape === "WITHHELD") {
		return (
			<div className="flex w-full min-w-0 flex-col gap-1">
				{/* The track the bar would fill, empty, at the bar's own height, place and width. */}
				<span aria-hidden className={cn("mt-5 w-full", HELD_BACK_TRACK)} />
				{/* The words are the cell's one accessible text, so nothing else repeats them. */}
				<p className="text-xs text-muted-foreground">{HELD_BACK}.</p>
			</div>
		);
	}
	const description = splitDescription(split, yourStanding, context);
	const parts = [
		...split.parts.map((part) => ({
			def: standingPart(part.standing),
			count: part.developers,
			isYours: context.readerCounted && part.standing === yourStanding,
		})),
		{
			def: NONE_YET_PART,
			count: split.noneYet ?? 0,
			isYours: context.readerCounted && !isSettledStanding(yourStanding),
		},
	];
	const total = parts.reduce((sum, part) => sum + part.count, 0);
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
							// Each count sits in its own part's column, centred under the part's piece of the bar,
							// and every column keeps room for its label, so no count shifts or meets another.
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
			{/* The whole the parts add up to, on its own line at the bar's end, so the counts stay centred
			    under their parts and the bar keeps the column's full width. */}
			<p aria-hidden className="text-right text-xs text-muted-foreground tabular-nums">
				{developerCount(total)}
			</p>
		</div>
	);
}

/**
 * A bar's shape while it loads, line for line as the bar lays it out: the marker's row, the bar,
 * the counts under it, then the total at its end.
 */
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

/**
 * A split beside a level's title, at the width the header's aside gives it, or its shape while the
 * level loads.
 */
export function LevelSplit({
	state,
}: {
	state: { status: "loading" } | ({ status: "ready"; context: SplitContext } & SplitOfRow);
}) {
	return (
		<div className="flex w-full sm:w-88">
			{state.status === "ready" ? (
				<WorkspaceSplitBar
					split={state.split}
					yourStanding={state.yourStanding}
					{...state.context}
				/>
			) : (
				<WorkspaceSplitBarSkeleton />
			)}
		</div>
	);
}

/**
 * What the bars' marks mean, once above each table of bars: each part's icon and swatch, the
 * reader's marker, and the dashed track of a split held back.
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
				<span aria-hidden className={cn("w-4", HELD_BACK_TRACK)} />
				Held back
			</li>
		</ul>
	);
}
