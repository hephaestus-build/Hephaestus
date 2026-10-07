import { useInView } from "motion/react";
import { type ReactNode, useEffect, useEffectEvent, useRef } from "react";

import { Button } from "@/components/ui/button";
import type { MorePages } from "@/runtime/tanstack-query/infinite-list";

export interface InfiniteListEndProps extends MorePages {
	/** "View earlier reviews". */
	moreLabel: string;
	/** "We could not load earlier reviews." */
	failedLabel: string;
	/** A skeleton in the shape of the list's own row. */
	loadingRow: ReactNode;
}

/**
 * The end of a paged list: it asks for the next page when it scrolls into view. The button stays,
 * because a keyboard needs it, and it is the only retry after a failure, so a failed page never
 * loads again on its own. TanStack Query ships no sentinel, so this pairs `useInfiniteQuery`'s
 * `fetchNextPage` (through `MorePages`) with motion's `useInView`.
 */
export function InfiniteListEnd({
	hasMore,
	isLoadingMore,
	isRefreshing = false,
	loadMoreError,
	onLoadMore,
	moreLabel,
	failedLabel,
	loadingRow,
}: InfiniteListEndProps) {
	const end = useRef<HTMLDivElement>(null);
	// Start the next page before the reader reaches the end.
	const inView = useInView(end, { margin: "0px 0px 240px 0px" });
	const failed = loadMoreError != null;
	const loadsByItself = inView && hasMore && !isLoadingMore && !isRefreshing && !failed;
	const loadMore = useEffectEvent(() => onLoadMore());
	// The effect syncs with the viewport, an external system. It reruns after each page and after each
	// refresh while the end stays in view, so a short page still fills the viewport.
	useEffect(() => {
		if (loadsByItself) {
			loadMore();
		}
	}, [loadsByItself]);

	if (!hasMore && !failed) {
		return null;
	}
	return (
		<div ref={end} className="flex flex-col gap-2.5">
			{isLoadingMore && (
				<div aria-hidden className="flex flex-col gap-2.5">
					{loadingRow}
				</div>
			)}
			<span className="flex flex-wrap items-center gap-2 text-sm">
				{failed && (
					<span role="alert" className="text-muted-foreground">
						{failedLabel}
					</span>
				)}
				<Button
					type="button"
					variant="link"
					size="inline"
					className="w-fit text-sm"
					onClick={onLoadMore}
					disabled={isLoadingMore}
					// The press that started the load keeps focus while the next rows arrive.
					focusableWhenDisabled
				>
					{pressLabel(isLoadingMore, failed, moreLabel)}
				</Button>
			</span>
		</div>
	);
}

function pressLabel(isLoadingMore: boolean, failed: boolean, moreLabel: string): string {
	if (isLoadingMore) {
		return "Loading…";
	}
	return failed ? "Retry" : moreLabel;
}
