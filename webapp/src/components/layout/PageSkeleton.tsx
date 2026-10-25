import { PageLayout } from "@/components/layout/PageLayout";
import { Skeleton } from "@/components/ui/skeleton";

/**
 * A page whose content is not known yet: the `PageHeader` title and description, then one section.
 * For a route that guards every page under it, so it cannot mirror a single page more closely.
 */
export function PageSkeleton() {
	return (
		<PageLayout aria-busy>
			<p className="sr-only">Loading page</p>
			<div className="space-y-2" aria-hidden>
				<Skeleton className="h-8 w-56 max-w-full" />
				<Skeleton className="h-4 w-full max-w-2xl" />
			</div>
			<div className="space-y-3" aria-hidden>
				<Skeleton className="h-7 w-48 max-w-full" />
				<Skeleton className="h-4 w-full" />
				<Skeleton className="h-4 w-full" />
				<Skeleton className="h-4 w-3/4" />
			</div>
		</PageLayout>
	);
}
