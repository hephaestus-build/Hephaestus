import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "cn";

import { PRACTICE_GROUP_STANDING_DEFS } from "./practice-group-standing-defs";
import { type StandingCounts, summarizeStandingCounts } from "./standing-counts";

const standingCountsListVariants = cva("", {
	variants: {
		size: {
			/** A table cell beside the small ring. */
			sm: "text-xs [&_svg]:size-3.5",
			/** The summary box beside the large ring. */
			md: "text-sm [&_svg]:size-4",
		},
	},
	defaultVariants: { size: "md" },
});

export interface StandingCountsListProps extends VariantProps<typeof standingCountsListVariants> {
	counts: StandingCounts;
	/** The list's accessible name: what the counts are of. */
	"aria-label": string;
	/** The items' layout; a column unless the caller lays them out otherwise. */
	className?: string;
}

/**
 * The legend beside a ring: one line per standing present, in the ring's own order, with the
 * count and the registry's short label. Plain text, so it carries no tooltip; the registry's
 * sentence stays where a standing is a badge. With nothing counted there is no list, since an
 * empty legend would claim a count of nothing.
 */
export function StandingCountsList({ counts, size, className, ...props }: StandingCountsListProps) {
	const segments = summarizeStandingCounts(counts);
	if (segments.length === 0) {
		return null;
	}
	return (
		<ul
			className={cn(
				"flex flex-col gap-px text-muted-foreground",
				standingCountsListVariants({ size }),
				className,
			)}
			{...props}
		>
			{segments.map(({ standing, count, colorClass, label }) => {
				const StandingIcon = PRACTICE_GROUP_STANDING_DEFS[standing].icon;
				return (
					<li key={standing} className="inline-flex items-center gap-1.5 whitespace-nowrap">
						<StandingIcon className={cn("shrink-0", colorClass)} aria-hidden />
						<span className="font-semibold text-foreground tabular-nums">{count}</span>
						{label}
					</li>
				);
			})}
		</ul>
	);
}
