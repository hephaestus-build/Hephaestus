import type { ReactNode } from "react";

import { cn } from "cn";
import type { WorkspaceRange, WorkspaceTile as WorkspaceTileFigure } from "@/api/types.gen";
import { StatTile } from "@/components/common/StatTile";
import { NEUTRAL_GREY } from "@/components/practice-vocabulary/standing-counts";
import { Skeleton } from "@/components/ui/skeleton";

export interface WorkspaceTileProps {
	title: string;
	icon: ReactNode;
	/** The reader's own value and the middle half of the developers counted, when anybody is. */
	figure: WorkspaceTileFigure;
	/** What the value counts: "so far", "of your 18 practices". */
	qualifier: string;
	/** Said in place of the band and pin when the whole middle half is at nought. */
	noneSentence?: string;
}

/**
 * The title's box: two lines tall wherever the tile's header is narrow enough to wrap a title, so a
 * loading tile and a loaded one share a height and the figures of a row line up. The header is the
 * nearest container.
 */
const TITLE_BOX = "flex min-h-5 items-center @max-[16rem]:min-h-10";

/**
 * One figure, the reader's own first and the workspace's middle half after, as Apple Health's
 * highlights set a value against "your typical range": the range in words, then as a band on a
 * track from nought with the reader's value pinned on it. The track shows no minimum and no
 * maximum, so no end of it points at one developer. One or two developers still get their band.
 */
export function WorkspaceTile({
	title,
	icon,
	figure,
	qualifier,
	noneSentence,
}: WorkspaceTileProps) {
	const { yours } = figure;
	const middle = figure.middle ?? undefined;
	return (
		<StatTile
			icon={icon}
			title={<span className={TITLE_BOX}>{title}</span>}
			value={yours}
			qualifier={qualifier}
		>
			<div className="mt-auto flex flex-col gap-1.5">
				{middle !== undefined && middle.high === 0 && noneSentence !== undefined ? (
					<p className="text-sm text-muted-foreground">{noneSentence}</p>
				) : (
					<>
						<p className="text-sm text-muted-foreground">
							{middle === undefined ? (
								"No developer is counted here yet."
							) : (
								<>
									Typical range:{" "}
									<span className="font-semibold text-foreground tabular-nums">
										{rangeText(middle)}
									</span>
								</>
							)}
						</p>
						{/* The axis is always there, so the reader's value reads against it; the band once anybody is counted. */}
						<RangeBar yours={yours} middle={middle} />
					</>
				)}
			</div>
		</StatTile>
	);
}

/** The track's end: a quarter past the larger of the band and the reader's value, at least one. */
function scaleOf(yours: number, high: number): number {
	return Math.max(1, Math.ceil(Math.max(yours, high) * 1.25));
}

/** The middle half in words, low to high: "11 to 21", or one value where both bounds agree. */
function rangeText({ low, high }: WorkspaceRange): string {
	return low === high ? `${low}` : `${low} to ${high}`;
}

/**
 * A track from nought with the reader's value pinned on it, and the middle half as a band on it
 * once anybody is counted.
 */
function RangeBar({ yours, middle }: { yours: number; middle?: WorkspaceRange }) {
	const scale = scaleOf(yours, middle?.high ?? 0);
	const at = (value: number) => `${(value / scale) * 100}%`;
	const range = middle === undefined ? "" : ` Typical range here: ${rangeText(middle)}.`;
	return (
		<div
			role="img"
			aria-label={`Your value: ${yours}.${range}`}
			className="relative mb-5 h-4 w-full"
		>
			<span aria-hidden className="absolute inset-x-0 top-1.5 h-1 rounded-full bg-muted" />
			{middle !== undefined && (
				<span
					aria-hidden
					className={cn(
						"absolute top-1 left-(--low) h-2 w-(--width) min-w-1 rounded-full bg-current",
						NEUTRAL_GREY,
					)}
					style={{ "--low": at(middle.low), "--width": at(middle.high - middle.low) }}
				/>
			)}
			<span
				aria-hidden
				className="absolute top-0 left-(--at) h-4 w-1 -translate-x-1/2 rounded-full bg-highlight ring-2 ring-card"
				style={{ "--at": at(yours) }}
			/>
			{/* The scale's two ends, so the band and the pin read against numbers. */}
			<span
				aria-hidden
				className="absolute inset-x-0 top-full flex justify-between pt-0.5 text-xs text-muted-foreground tabular-nums"
			>
				<span>0</span>
				<span>{scale}</span>
			</span>
		</div>
	);
}

/**
 * The tile's shape while its figure loads, line for line at the loaded tile's heights: the icon and
 * title, the value and what it counts, the range in words, then the track with a band on it and the
 * scale's two ends under it. Static under reduced motion, as every `Skeleton` is.
 */
export function WorkspaceTileSkeleton() {
	return (
		<div aria-hidden className="flex w-full" data-slot="tile-skeleton">
			<StatTile
				icon={<Skeleton className="size-4 shrink-0 rounded-full" />}
				title={
					<span className={TITLE_BOX}>
						<Skeleton className="h-3.5 w-36" />
					</span>
				}
				// The value's line with what it counts beside it, at the loaded line's height.
				value={
					<span className="flex h-6.5 items-center gap-1.5">
						<Skeleton className="h-6 w-8" />
						<Skeleton className="mt-1 h-3.5 w-28" />
					</span>
				}
			>
				<div className="mt-auto flex flex-col gap-1.5">
					<span className="flex h-5 items-center">
						<Skeleton className="h-3.5 w-36" />
					</span>
					<div className="relative mb-5 h-4 w-full">
						<span className="absolute inset-x-0 top-1.5 h-1 rounded-full bg-muted" />
						<Skeleton className="absolute top-1 left-[30%] h-2 w-[35%] rounded-full bg-muted-foreground/15" />
						<span className="absolute inset-x-0 top-full flex h-4.5 items-end justify-between">
							<Skeleton className="h-3 w-2" />
							<Skeleton className="h-3 w-4" />
						</span>
					</div>
				</div>
			</StatTile>
		</div>
	);
}
