import { CircleIcon, TriangleIcon } from "lucide-react";
import type { ComponentType } from "react";

import { cn } from "cn";
import type { WorkspaceSplit } from "@/api/types.gen";
import { statusToneClass } from "@/components/common/status-def";
import {
	PRACTICE_GROUP_STANDING_DEFS,
	type PracticeGroupStandingValue,
} from "@/components/practice-vocabulary/practice-group-standing-defs";

import {
	developerCount,
	HELD_BACK,
	isSplitStanding,
	NONE_YET,
	SPLIT_FIELDS,
	SPLIT_STANDINGS,
	type SplitContext,
	type SplitStanding,
	splitDescription,
} from "./across-workspace-copy";

export interface WorkspaceSplitBarProps extends SplitContext {
	split: WorkspaceSplit;
	/**
	 * The reader's current standing in the group or the practice: the part the You marker is on,
	 * while the reader is counted and the split shows.
	 */
	yourStanding: PracticeGroupStandingValue;
}

interface Part {
	key: string;
	count: number;
	label: string;
	icon: ComponentType<{ className?: string }>;
	/** The icon's colour under the bar: the standing's tone, or the grey of a part with none. */
	tone: string;
	/** The ground of the part's piece of the bar. */
	className: string;
	/** Whether the reader is one of the developers this part counts. */
	isYours: boolean;
}

/**
 * None yet holds both standings that say why there is none, so it takes a neutral empty circle
 * rather than either one's icon, and a fill in the one grey family that stays a part of the bar
 * against the card in both themes. The fill stays under 3:1 against the card, since the count and
 * icon under every part carry what it shows.
 */
const NONE_YET_ICON = CircleIcon;
const NONE_YET_FILL = "bg-muted-foreground/40";
const GREY = "text-muted-foreground";
/** The empty track of a split held back, dashed so it is never read as a part of none yet. */
const HELD_BACK_TRACK = "h-2 rounded-sm border border-dashed border-muted-foreground/60";

/** A standing's part, in the registry's tone, every part at one strength. */
function standingFill(standing: SplitStanding): string {
	return cn("bg-current", statusToneClass(PRACTICE_GROUP_STANDING_DEFS[standing].badgeVariant));
}

function partsOf(
	split: WorkspaceSplit,
	yourStanding: PracticeGroupStandingValue,
	readerCounted: boolean,
): Part[] {
	const noneYet: Part = {
		key: "none",
		count: split.noneYet ?? 0,
		label: NONE_YET,
		icon: NONE_YET_ICON,
		tone: GREY,
		className: NONE_YET_FILL,
		isYours: readerCounted && !isSplitStanding(yourStanding),
	};
	const standings = SPLIT_STANDINGS.map((standing): Part => {
		const def = PRACTICE_GROUP_STANDING_DEFS[standing];
		const isYours = readerCounted && standing === yourStanding;
		return {
			key: standing,
			count: split[SPLIT_FIELDS[standing]] ?? 0,
			label: def.label,
			icon: def.icon,
			// The status colour reaches the bar and the icon through the registry, never written here.
			tone: statusToneClass(def.badgeVariant),
			className: standingFill(standing),
			isYours,
		};
	});
	return [...standings, noneYet];
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
	const parts = partsOf(split, yourStanding, context.readerCounted);
	const total = parts.reduce((sum, part) => sum + part.count, 0);
	return (
		<div className="flex w-full min-w-0 flex-col gap-1">
			<div role="img" aria-label={description} className="flex w-full min-w-0 gap-0.5">
				{parts.map((part) => {
					const Icon = part.icon;
					return (
						<div
							key={part.key}
							aria-hidden
							title={`${part.label}: ${part.count}`}
							// Each count sits in its own part's column, centred under the part's piece of the bar,
							// and every column keeps room for its label, so no count shifts or meets another.
							className="flex min-w-10 grow-(--count) basis-0 flex-col items-center gap-0.5"
							style={{ "--count": part.count }}
						>
							<span className="flex h-5 flex-col items-center justify-end text-mentor">
								{part.isYours && (
									<>
										<span className="text-xs leading-3 font-semibold">You</span>
										<TriangleIcon className="size-2.5 rotate-180 fill-current" />
									</>
								)}
							</span>
							<span className={cn("h-2 w-full rounded-sm", part.className)} />
							<span className="flex w-full items-center justify-center gap-1 text-xs text-muted-foreground tabular-nums">
								<Icon className={cn("size-3 shrink-0", part.tone)} />
								{part.count}
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

/** A split beside a level's title, at the width the header's aside gives it. */
export function LevelSplit({
	split,
	yourStanding,
	context,
}: {
	split: WorkspaceSplit;
	yourStanding: PracticeGroupStandingValue;
	context: SplitContext;
}) {
	return (
		<div className="flex w-full sm:w-88">
			<WorkspaceSplitBar split={split} yourStanding={yourStanding} {...context} />
		</div>
	);
}

/**
 * What the bars' marks mean, once above each table of bars: each part's icon and swatch, the
 * reader's marker, and the dashed track of a split held back.
 */
export function SplitLegend() {
	const items = [
		...SPLIT_STANDINGS.map((standing) => {
			const def = PRACTICE_GROUP_STANDING_DEFS[standing];
			return {
				key: standing,
				label: def.label,
				icon: def.icon,
				// The icon in its standing's colour, as the badge colours it.
				tone: statusToneClass(def.badgeVariant),
				swatch: standingFill(standing),
			};
		}),
		{ key: "none", label: NONE_YET, icon: NONE_YET_ICON, tone: GREY, swatch: NONE_YET_FILL },
	];
	return (
		<ul
			aria-label="What the bars show"
			className="flex flex-wrap items-center gap-x-4 gap-y-1.5 text-xs text-muted-foreground"
		>
			{items.map((item) => {
				const Icon = item.icon;
				return (
					<li key={item.key} className="inline-flex items-center gap-1.5">
						<span aria-hidden className={cn("h-2 w-4 rounded-sm", item.swatch)} />
						<Icon aria-hidden className={cn("size-3 shrink-0", item.tone)} />
						{item.label}
					</li>
				);
			})}
			<li className="inline-flex items-center gap-1 text-mentor">
				<TriangleIcon aria-hidden className="size-2.5 rotate-180 fill-current" />
				<span className="font-semibold">You</span>
				<span className="text-muted-foreground">marks your part</span>
			</li>
			<li className="inline-flex items-center gap-1.5">
				<span aria-hidden className={cn("w-4", HELD_BACK_TRACK)} />
				Held back
			</li>
		</ul>
	);
}
