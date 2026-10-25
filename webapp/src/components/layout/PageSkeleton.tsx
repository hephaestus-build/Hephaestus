import { useEffect, useRef } from "react";

import { PageLayout } from "@/components/layout/PageLayout";
import { Skeleton } from "@/components/ui/skeleton";

/**
 * A page whose content is not known yet: the `PageHeader` title and description, then one section.
 * For a route that guards every page under it, so it cannot mirror a single page more closely.
 *
 * The status text is written one frame after the region mounts, as in `RouteAnnouncer`: a live
 * region announces a change, and text that is already inside when it mounts is not one (ARIA22).
 * No `aria-busy` around it, because a busy ancestor can hold back the announcement.
 */
export function PageSkeleton() {
	const status = useRef<HTMLParagraphElement>(null);
	useEffect(() => {
		const frame = requestAnimationFrame(() => {
			if (status.current) {
				status.current.textContent = "Loading page…";
			}
		});
		return () => cancelAnimationFrame(frame);
	}, []);
	return (
		<PageLayout>
			<p ref={status} role="status" className="sr-only" />
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
