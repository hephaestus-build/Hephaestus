import { useInView } from "motion/react";
import { type ReactNode, useEffect, useEffectEvent, useRef } from "react";

import { Button } from "@/components/ui/button";
import type { MorePages } from "@/runtime/tanstack-query/infinite-list";

export interface InfiniteListEndProps extends MorePages {
	/** What a press loads, in the list's own words: "View earlier reviews". */
	moreLabel: string;
	/** What the list says when the next page failed: "We could not load earlier reviews." */
	failedLabel: string;
	/** What stands for the next page while it loads, in the shape of the list's own row. */
	loadingRow: ReactNode;
}

/**
 * The end of a list the server pages: the next page loads by itself once this end scrolls into
 * view, a loading row stands in for it while it comes, and a failure says so beside the press that
 * asks again. The press stays the whole time, as the way a keyboard asks for the next page and the
 * only way after a failure, so a list never loads again and again on its own after one failed. A
 * list with nothing left to load ends here with nothing.
 */
export function InfiniteListEnd({
	hasMore,
	isLoadingMore,
	loadMoreError,
	onLoadMore,
	moreLabel,
	failedLabel,
	loadingRow,
}: InfiniteListEndProps) {
	const end = useRef<HTMLDivElement>(null);
	// A margin under the viewport, so the next page is on its way before the reader reaches the end.
	const inView = useInView(end, { margin: "0px 0px 240px 0px" });
	const failed = loadMoreError != null;
	const loadsByItself = inView && hasMore && !isLoadingMore && !failed;
	const loadMore = useEffectEvent(() => onLoadMore());
	// Asks again after each page while the end stays in view, so a short page fills the viewport.
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
				>
					{pressLabel(isLoadingMore, failed, moreLabel)}
				</Button>
			</span>
		</div>
	);
}

/** The press in the list's words, or what it is doing instead. */
function pressLabel(isLoadingMore: boolean, failed: boolean, moreLabel: string): string {
	if (isLoadingMore) {
		return "Loading…";
	}
	return failed ? "Retry" : moreLabel;
}
