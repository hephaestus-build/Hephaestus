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

	return (
		// Stays after the last page, so the focus of a press that loaded it has a place to stay.
		<div tabIndex={-1} data-list-end className="outline-none">
			{(hasMore || failed) && (
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
							ref={keepFocusAtTheEnd}
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
			)}
		</div>
	);
}

/**
 * Gives focus to the end of the list when the press that has it goes, after the last page. React
 * detaches a ref before it removes the element, so the press still has focus here. Declared outside
 * the component, so the cleanup runs only when the press goes and not on each render.
 */
function keepFocusAtTheEnd(press: HTMLButtonElement | null) {
	return () => {
		if (press !== null && press === document.activeElement) {
			press.closest<HTMLElement>("[data-list-end]")?.focus();
		}
	};
}

function pressLabel(isLoadingMore: boolean, failed: boolean, moreLabel: string): string {
	if (isLoadingMore) {
		return "Loading…";
	}
	return failed ? "Retry" : moreLabel;
}
