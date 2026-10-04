import type { ReactNode } from "react";

import { cn } from "cn";
import type { WorkspaceRange, WorkspaceTile as WorkspaceTileFigure } from "@/api/types.gen";
import { StatTile, StatTileSkeleton } from "@/components/common/StatTile";
import { NEUTRAL_GREY } from "@/components/practice-vocabulary/standing-counts";
import { Skeleton } from "@/components/ui/skeleton";

export interface WorkspaceTileProps {
	title: string;
	icon: ReactNode;
	/** The reader's own value and the middle half of the developers with a standing, when it may show. */
	figure: WorkspaceTileFigure;
	/** What the value counts: "so far", "of your 18 practices". */
	qualifier: string;
	/** Said in place of the band and pin when the whole middle half is at nought. */
	noneSentence?: string;
}

/**
 * One figure, the reader's own first and the workspace's middle half after, as Apple Health's
 * highlights set a value against "your typical range": the range in words, then as a band on a
 * track from nought with the reader's value pinned on it, then the reference group it is the middle
 * half of. The track shows no minimum and no maximum, so no end of it points at one developer.
 */
export function WorkspaceTile({
	title,
	icon,
	figure,
	qualifier,
	noneSentence,
}: WorkspaceTileProps) {
	const { yours, middle } = figure;
	return (
		<StatTile icon={icon} title={title} value={yours} qualifier={qualifier}>
			<div className="mt-auto flex flex-col gap-1.5">
				{middle !== undefined && middle.high === 0 && noneSentence !== undefined ? (
					<p className="text-sm text-muted-foreground">{noneSentence}</p>
				) : (
					<>
						<p className="text-sm text-muted-foreground">
							{middle === undefined ? (
								"Needs more data before the workspace shows here."
							) : (
								<>
									Typical range:{" "}
									<span className="font-semibold text-foreground tabular-nums">
										{rangeText(middle)}
									</span>
								</>
							)}
						</p>
						{/* The axis is always there, so the reader's value reads against it; the band once it may show. */}
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
 * once the workspace may show.
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
				className="absolute top-0 left-(--at) h-4 w-1 -translate-x-1/2 rounded-full bg-mentor ring-2 ring-card"
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

/** The tile's shape while its figure loads: the range in words, then its track with the scale under it. */
export function WorkspaceTileSkeleton() {
	return (
		<StatTileSkeleton>
			<div className="mt-auto flex flex-col gap-1.5">
				<Skeleton className="h-5 w-36" />
				<Skeleton className="mb-5 h-4 w-full" />
			</div>
		</StatTileSkeleton>
	);
}
