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
					// Each blank sits in the line box its text will fill, so the facts land without a jump.
					<div key={index} className="space-y-0.5">
						<dt className="flex h-4 items-center">
							<Skeleton className="h-3 w-20" />
						</dt>
						<dd className="flex h-5 items-center">
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
