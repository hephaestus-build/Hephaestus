import { PageLayout } from "@/components/layout/PageLayout";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody } from "@/components/ui/table";

import { PeopleSkeletonRows } from "./PeopleSkeletonRows";

/**
 * The page's shape while it is read for the first time: the header, the range controls and the
 * people table. No name, filter or figure stands in for what has not arrived.
 */
export function PublicActivityPageSkeleton() {
	return (
		<PageLayout className="space-y-8" aria-busy>
			<p className="sr-only">Loading public activity</p>
			<div className="flex items-start gap-3" aria-hidden>
				<Skeleton className="mt-1 size-6 shrink-0" />
				<div className="min-w-0 flex-1 space-y-2">
					<Skeleton className="h-8 w-56 max-w-full" />
					<Skeleton className="h-4 w-full max-w-2xl" />
				</div>
			</div>
			<div className="flex sm:justify-end" aria-hidden>
				<Skeleton className="h-8 w-full sm:w-80" />
			</div>
			<div className="space-y-3" aria-hidden inert>
				<div className="space-y-2">
					<Skeleton className="h-7 w-48" />
					<Skeleton className="h-4 w-full max-w-2xl" />
				</div>
				<Skeleton className="h-8 w-full sm:w-64" />
				<Table bordered>
					<TableBody>
						<PeopleSkeletonRows />
					</TableBody>
				</Table>
			</div>
		</PageLayout>
	);
}
