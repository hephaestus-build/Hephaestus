import type { ReactNode } from "react";

import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";

import { type StandingCounts, StandingCountsList } from "./PracticeGroupStandingRing";

export interface StandingSummaryBoxProps {
	/** The large ring beside the counts: `PracticeGroupStandingRing` at its default size. */
	ring: ReactNode;
	/** "12 practices in 4 groups", "5 practices in this group". */
	label: string;
	/**
	 * How many practices sit at each standing; the legend lists the ones present in the ring's own
	 * order.
	 */
	counts: StandingCounts;
	/** A control on the label's row, right-aligned. */
	action?: ReactNode;
	isLoading?: boolean;
	className?: string;
}

/**
 * The hairline box that sums up a set of practices: a ring, the count of practices it stands
 * for, and one line per standing present with its count. The page header and the group level
 * share it so the two read as the same box.
 */
export function StandingSummaryBox({
	ring,
	label,
	counts,
	action,
	isLoading = false,
	className,
}: StandingSummaryBoxProps) {
	return (
		<div
			className={cn(
				"inline-flex max-w-full items-center gap-4 rounded-xl border bg-background p-4",
				className,
			)}
		>
			{isLoading ? <Skeleton className="size-15 shrink-0 rounded-full" /> : ring}
			<div className="flex min-w-0 flex-1 flex-col gap-1.5">
				<div className="flex flex-wrap items-center justify-between gap-x-6 gap-y-1.5">
					{isLoading ? (
						<Skeleton className="h-4 w-36" />
					) : (
						<span className="text-xs font-semibold text-muted-foreground">{label}</span>
					)}
					{action}
				</div>
				{isLoading ? (
					<div className="grid grid-cols-[max-content] gap-x-4 gap-y-1.5 sm:grid-cols-[repeat(3,max-content)]">
						<Skeleton className="h-5 w-24" />
						<Skeleton className="h-5 w-28" />
						<Skeleton className="h-5 w-28" />
					</div>
				) : (
					<StandingCountsList
						counts={counts}
						aria-label="Practices by standing"
						className="grid grid-cols-[max-content] items-center gap-x-4 gap-y-1.5 sm:grid-cols-[repeat(3,max-content)]"
					/>
				)}
			</div>
		</div>
	);
}
