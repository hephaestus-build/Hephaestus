import type { ComponentProps } from "react";

import { cn } from "cn";
import { Skeleton } from "@/components/ui/skeleton";
import { TabsList, TabsTrigger } from "@/components/ui/tabs";

/**
 * The tab row the practice surfaces draw over a list — the profile page over its feedback cards,
 * the practice level over its observations. A hairline rail is drawn on the row itself, at its
 * bottom edge, under the tabs and whatever sits beside them; the tabs sit on that same edge, so
 * the active tab's 2 px bar covers the rail rather than hanging under it. The row wraps at 320px,
 * where labelled tabs with their counts outrun the line.
 */
export function PracticeTabsRail({ className, ...props }: ComponentProps<"div">) {
	return (
		<div
			className={cn(
				"relative flex flex-wrap items-end justify-between gap-x-6 gap-y-2 after:pointer-events-none after:absolute after:inset-x-0 after:bottom-0 after:h-px after:bg-border",
				className,
			)}
			{...props}
		/>
	);
}

export function PracticeTabsList({ className, ...props }: ComponentProps<typeof TabsList>) {
	return (
		<TabsList
			variant="line"
			// `h-auto` restated under the orientation variant, which otherwise wins with its `h-8` and
			// lets a wrapped second row of tabs spill over the content.
			className={cn(
				"relative z-[1] h-auto flex-wrap justify-start gap-x-4 gap-y-1 p-0 group-data-[orientation=horizontal]/tabs:h-auto",
				className,
			)}
			{...props}
		/>
	);
}

export interface PracticeTabsTriggerProps extends ComponentProps<typeof TabsTrigger> {
	/** How many the tab lists; left out while that is not yet known. */
	count?: number;
}

export function PracticeTabsTrigger({
	count,
	className,
	children,
	...props
}: PracticeTabsTriggerProps) {
	return (
		<TabsTrigger
			className={cn(
				"h-auto flex-none gap-1.5 px-0 pt-0 pb-2 text-sm group-data-[orientation=horizontal]/tabs:after:bottom-[-0.5px] data-active:font-semibold",
				className,
			)}
			{...props}
		>
			{children}
			{count !== undefined && (
				// The space is the tab's accessible name: "Observations 3", not "Observations3".
				<>
					{" "}
					<span className="font-normal text-muted-foreground tabular-nums">{count}</span>
				</>
			)}
		</TabsTrigger>
	);
}

/** The rail with tab-shaped blanks on it, while what the tabs will count is still loading. */
export function PracticeTabsSkeleton({ tabs = 3 }: { tabs?: number }) {
	return (
		<PracticeTabsRail aria-hidden>
			<div className="flex gap-x-4 pb-2">
				{Array.from({ length: tabs }, (_, index) => (
					<Skeleton key={index} className="h-5 w-24" />
				))}
			</div>
		</PracticeTabsRail>
	);
}
