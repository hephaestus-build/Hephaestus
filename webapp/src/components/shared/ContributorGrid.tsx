import { cn } from "cn";
import { Skeleton } from "@/components/ui/skeleton";

import { type Contributor, ContributorCard } from "./ContributorCard";

// Re-export the Contributor type for convenience
export type { Contributor } from "./ContributorCard";

interface ContributorGridProps {
	contributors: Contributor[];
	isLoading?: boolean;
	size?: "sm" | "md";
	layout?: "compact" | "comfortable";
	emptyState?: React.ReactNode;
	loadingSkeletonCount?: number;
	className?: string;
}

/**
 * ContributorGrid component for displaying a grid of contributors
 * with customizable layout and loading states.
 */
export function ContributorGrid({
	contributors,
	isLoading = false,
	size = "md",
	layout = "compact",
	emptyState,
	loadingSkeletonCount = 10,
	className,
}: ContributorGridProps) {
	const gridClass = cn(
		"grid",
		layout === "compact"
			? "grid-cols-2 sm:grid-cols-4 md:grid-cols-6 lg:grid-cols-8 xl:grid-cols-10 gap-3"
			: "grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-6 xl:grid-cols-8 gap-4",
		className,
	);

	const skeletonSize = size === "sm" ? "h-12 w-12" : "h-16 w-16";

	if (isLoading) {
		return (
			<div className={gridClass}>
				{Array.from({ length: loadingSkeletonCount }).map((_, index) => (
					<div key={index} className="flex flex-col items-center gap-1">
						<Skeleton className={cn(skeletonSize, "rounded-full")} />
						<Skeleton className="h-3 w-16" />
						<Skeleton className="h-2 w-12" />
					</div>
				))}
			</div>
		);
	}

	if (contributors.length === 0 && emptyState) {
		return <>{emptyState}</>;
	}

	return (
		<div className={gridClass}>
			{contributors.map((contributor) => (
				<ContributorCard key={contributor.id} contributor={contributor} size={size} />
			))}
		</div>
	);
}
