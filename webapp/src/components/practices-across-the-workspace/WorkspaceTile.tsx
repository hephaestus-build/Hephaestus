import type { ReactNode } from "react";

import type { WorkspaceTile as WorkspaceTileFigure } from "@/api/types.gen";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

import { type AcrossWorkspaceWindow, developerCount, windowPhrase } from "./across-workspace-copy";

export interface WorkspaceTileProps {
	title: string;
	icon: ReactNode;
	/** The reader's own value and the middle half of the observed developers, when it may show. */
	figure: WorkspaceTileFigure;
	/** What the value counts: "this term", "of your 18 practices". */
	qualifier: string;
	/** One line under the value on where it comes from. */
	note?: string;
	/** The reference group the middle half is a part of: "24 developers observed this term". */
	observedDevelopers: number;
	window: AcrossWorkspaceWindow;
	/** Off, the tile shows the reader's own value and nothing about the workspace. */
	showWorkspace: boolean;
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
	note,
	observedDevelopers,
	window,
	showWorkspace,
}: WorkspaceTileProps) {
	const { yours, middleLow, middleHigh } = figure;
	const middle =
		middleLow !== undefined && middleHigh !== undefined
			? { low: middleLow, high: middleHigh }
			: undefined;
	return (
		<Card size="sm" className="w-full">
			<CardHeader>
				<CardTitle className="flex items-center gap-2 text-sm font-medium">
					{icon}
					{title}
				</CardTitle>
			</CardHeader>
			<CardContent className="flex flex-1 flex-col gap-1.5">
				<p className="flex items-baseline gap-1.5">
					<span className="text-2xl leading-none font-semibold tabular-nums">{yours}</span>
					<span className="text-sm text-muted-foreground">{qualifier}</span>
				</p>
				{note !== undefined && <p className="text-xs text-muted-foreground">{note}</p>}
				{showWorkspace &&
					(middle === undefined ? (
						<p className="mt-auto pt-1 text-sm text-muted-foreground">
							Needs more data before the workspace shows here.
						</p>
					) : (
						<div className="mt-auto flex flex-col gap-1.5 pt-1">
							<p className="text-sm text-muted-foreground">
								Most developers here:{" "}
								<span className="font-semibold text-foreground tabular-nums">
									{middle.low === middle.high ? middle.low : `${middle.low} to ${middle.high}`}
								</span>
							</p>
							<RangeBar yours={yours} low={middle.low} high={middle.high} />
							<p className="text-xs text-muted-foreground">
								Middle half of {developerCount(observedDevelopers)} observed {windowPhrase(window)}
							</p>
						</div>
					))}
			</CardContent>
		</Card>
	);
}

/** The track's end: a quarter past the larger of the band and the reader's value, at least one. */
function scaleOf(yours: number, high: number): number {
	return Math.max(1, Math.ceil(Math.max(yours, high) * 1.25));
}

/** The middle half as a band on a track from nought, the reader's value as a pin on it. */
function RangeBar({ yours, low, high }: { yours: number; low: number; high: number }) {
	const scale = scaleOf(yours, high);
	const at = (value: number) => `${(value / scale) * 100}%`;
	return (
		<div
			role="img"
			aria-label={`You: ${yours}. The middle half of developers here: ${low === high ? low : `${low} to ${high}`}.`}
			className="relative h-4 w-full"
		>
			<span aria-hidden className="absolute inset-x-0 top-1.5 h-1 rounded-full bg-muted" />
			<span
				aria-hidden
				className="absolute top-1 left-(--low) h-2 w-(--width) min-w-1 rounded-full bg-muted-foreground/50"
				style={{ "--low": at(low), "--width": at(high - low) }}
			/>
			<span
				aria-hidden
				className="absolute top-0 left-(--at) h-4 w-1 -translate-x-1/2 rounded-full bg-mentor ring-2 ring-card"
				style={{ "--at": at(yours) }}
			/>
		</div>
	);
}

/** The tile's shape while the figures load. */
export function WorkspaceTileSkeleton() {
	return (
		<Card size="sm" className="w-full" aria-hidden>
			<CardHeader>
				<Skeleton className="h-4 w-36" />
			</CardHeader>
			<CardContent className="flex flex-col gap-2">
				<Skeleton className="h-7 w-20" />
				<Skeleton className="h-4 w-44" />
				<Skeleton className="h-4 w-full" />
			</CardContent>
		</Card>
	);
}
