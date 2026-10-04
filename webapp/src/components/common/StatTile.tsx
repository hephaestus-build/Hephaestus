import type { ComponentProps, ReactNode } from "react";

import { cn } from "cn";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

/**
 * The grid stat tiles sit in: one column, then two, then four across, by the width the grid gets
 * rather than the viewport's. The caller wraps it in an `@container`.
 */
export const STAT_TILE_GRID = "grid grid-cols-1 gap-3 @sm:grid-cols-2 @2xl:grid-cols-4";

export interface StatTileProps {
	/** `interactive` when the whole tile is a link, `muted` when it has nothing to show. */
	variant?: ComponentProps<typeof Card>["variant"];
	/** The glyph before the title, already toned by the caller. */
	icon: ReactNode;
	title: ReactNode;
	/** The headline figure. */
	value: ReactNode;
	/** What the figure counts, after it: "of your 18 practices". */
	qualifier?: string;
	/** Under the figure, in the same block: a comparison with the period before. */
	detail?: ReactNode;
	/** The figure in the muted tone, for a tile with nothing behind it. */
	muted?: boolean;
	/** What the tile shows under the figure: bars, a range, chips. */
	children?: ReactNode;
}

/**
 * One stat tile, as Activity and Practices across the workspace draw them, after the stat tiles of
 * GitHub's Pulse and Apple Health's summary: the title with its glyph, the headline figure and what
 * it counts, then whatever the page shows under it.
 */
export function StatTile({
	variant,
	icon,
	title,
	value,
	qualifier,
	detail,
	muted = false,
	children,
}: StatTileProps) {
	return (
		<Card variant={variant} size="sm" className="w-full">
			<CardHeader>
				<CardTitle className="flex items-center gap-2 text-sm font-medium">
					{icon}
					{title}
				</CardTitle>
			</CardHeader>
			<CardContent className="flex flex-1 flex-col gap-3">
				<div className="space-y-1">
					<p className="flex items-baseline gap-1.5">
						<span
							className={cn(
								"text-2xl leading-none font-semibold tabular-nums",
								muted ? "text-muted-foreground" : "text-foreground",
							)}
						>
							{value}
						</span>
						{qualifier !== undefined && (
							<span className="text-sm text-muted-foreground">{qualifier}</span>
						)}
					</p>
					{detail}
				</div>
				{children}
			</CardContent>
		</Card>
	);
}

/**
 * A tile's shape while its figure loads, line for line as `StatTile` lays it out: the title, the
 * figure, then the page's own content in the shape it will take.
 */
export function StatTileSkeleton({ children }: { children?: ReactNode }) {
	return (
		<Card size="sm" className="w-full" aria-hidden>
			<CardHeader>
				<Skeleton className="h-5 w-36" />
			</CardHeader>
			<CardContent className="flex flex-1 flex-col gap-3">
				<Skeleton className="h-6 w-24" />
				{children}
			</CardContent>
		</Card>
	);
}
