import { Skeleton } from "@/components/ui/skeleton";

import { ReviewFactGrid } from "./ReviewFactGrid";

/**
 * The body every record level resolves into — a grid of facts, then sections of prose and rows —
 * drawn while the record loads, inside the level's real `DrawerBody`.
 */
export function LevelBodySkeleton() {
	return (
		<div className="space-y-8" aria-hidden>
			<ReviewFactGrid>
				{Array.from({ length: 3 }, (_, index) => (
					<div key={index} className="space-y-2">
						<dt>
							<Skeleton className="h-3 w-20" />
						</dt>
						<dd>
							<Skeleton className="h-4 w-40" />
						</dd>
					</div>
				))}
			</ReviewFactGrid>
			<div className="space-y-3">
				<Skeleton className="h-5 w-48" />
				<Skeleton className="h-4 w-full" />
				<Skeleton className="h-4 w-full max-w-lg" />
			</div>
			<div className="space-y-3">
				<Skeleton className="h-5 w-40" />
				<Skeleton className="h-24 w-full" />
			</div>
		</div>
	);
}
