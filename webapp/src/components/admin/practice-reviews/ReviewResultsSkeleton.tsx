import { Item, ItemActions, ItemContent, ItemMedia } from "@/components/ui/item";
import { Skeleton } from "@/components/ui/skeleton";

export interface ReviewResultsSkeletonProps {
	label: string;
	/**
	 * The page size the list is about to show. Deliberately undefaulted: any default is a row count
	 * that can silently disagree with the caller's page size, and the results then arrive by pushing
	 * the pagination up or down the screen — the jump a skeleton exists to prevent.
	 */
	rows: number;
}

/** The shape of {@link ReviewRow} in {@link ReviewRowList}, before the rows exist. */
export function ReviewResultsSkeleton({ label, rows }: ReviewResultsSkeletonProps) {
	return (
		<div className="overflow-hidden rounded-xl border bg-card" role="status">
			<span className="sr-only">{label}</span>
			{Array.from({ length: rows }, (_, index) => (
				<Item key={index} variant="row" size="sm" className="items-start" aria-hidden>
					<ItemMedia className="mt-0.5">
						<Skeleton className="size-4 rounded-full" />
					</ItemMedia>
					<ItemContent className="min-w-0 basis-48 gap-1.5">
						<Skeleton className="h-4 w-full max-w-80" />
						<Skeleton className="h-3 w-full max-w-56" />
					</ItemContent>
					<ItemActions className="gap-1.5">
						<Skeleton className="size-6 rounded-full" />
						<Skeleton className="h-3 w-24" />
					</ItemActions>
				</Item>
			))}
		</div>
	);
}
