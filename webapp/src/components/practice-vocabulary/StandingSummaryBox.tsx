import type { ReactNode } from "react";

import { cn } from "cn";
import { Skeleton } from "@/components/ui/skeleton";

import { PracticeGroupStandingRing } from "./PracticeGroupStandingRing";
import type { StandingCounts } from "./standing-counts";
import { StandingCountsList } from "./StandingCountsList";

/**
 * Where the box sits. `fit` is as wide as it needs beside a title, the legend in columns; `fill`
 * is a card across the page, the legend on one line that wraps and `children` at its end, under
 * the legend below `sm`.
 */
type StandingSummaryLayout = "fit" | "fill";

const LAYOUTS: Record<StandingSummaryLayout, { box: string; text: string; legend: string }> = {
	fit: {
		box: "inline-flex max-w-full gap-4",
		text: "flex-1",
		legend: "grid grid-cols-[max-content] gap-x-4 gap-y-1.5 sm:grid-cols-[repeat(3,max-content)]",
	},
	fill: {
		box: "flex w-full flex-wrap gap-x-6 gap-y-3",
		text: "flex-1 basis-40",
		legend: "flex flex-row flex-wrap gap-x-4 gap-y-1",
	},
};

export interface StandingSummaryBoxProps {
	/** "12 practices in 4 groups", "5 practices in this group". */
	label: string;
	/**
	 * How many practices sit at each standing: the ring draws them, and the legend lists the ones
	 * present in the ring's own order.
	 */
	counts: StandingCounts;
	isLoading?: boolean;
	layout?: StandingSummaryLayout;
	/** Where the summary leads, after the legend; it stands while the counts load. */
	children?: ReactNode;
	className?: string;
}

/**
 * The hairline box that sums up a set of practices: a ring, the count of practices it stands
 * for, and one line per standing present with its count. A practice group's level wears it
 * beside its title; the page header lays it across the page with its destination at the end.
 */
export function StandingSummaryBox({
	label,
	counts,
	isLoading = false,
	layout = "fit",
	children,
	className,
}: StandingSummaryBoxProps) {
	const { box, text, legend } = LAYOUTS[layout];
	return (
		<div className={cn("items-center rounded-xl border bg-background p-4", box, className)}>
			{isLoading ? (
				<Skeleton className="size-15 shrink-0 rounded-full" />
			) : (
				<PracticeGroupStandingRing counts={counts} />
			)}
			<div className={cn("flex min-w-0 flex-col gap-1.5", text)}>
				{isLoading ? (
					<Skeleton className="h-4 w-36" />
				) : (
					<span className="text-xs font-semibold text-muted-foreground">{label}</span>
				)}
				{isLoading ? (
					<div className={legend}>
						<Skeleton className="h-5 w-24" />
						<Skeleton className="h-5 w-28" />
						<Skeleton className="h-5 w-28" />
					</div>
				) : (
					<StandingCountsList
						counts={counts}
						aria-label="Practices by standing"
						className={cn("items-center", legend)}
					/>
				)}
			</div>
			{children}
		</div>
	);
}
