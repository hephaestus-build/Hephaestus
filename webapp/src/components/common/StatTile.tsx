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
	/**
	 * `interactive` when the whole tile is a link. `muted` when it has nothing to show, which mutes
	 * the figure too.
	 */
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
	/** What the tile shows under the figure: bars, a range, chips. */
	children?: ReactNode;
}

/** The stat tile Activity and Practices across the workspace share, after GitHub Pulse and Apple Health. */
export function StatTile({
	variant,
	icon,
	title,
	value,
	qualifier,
	detail,
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
								variant === "muted" ? "text-muted-foreground" : "text-foreground",
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

/** `StatTile`'s lines while its figure loads; `children` is the page's content in its loading shape. */
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
