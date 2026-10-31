import { useInView } from "motion/react";
import { useEffect, useEffectEvent, useRef } from "react";

import { Button } from "@/components/ui/button";
import type { MorePages } from "@/runtime/tanstack-query/infinite-list";

/**
 * The placeholder rows a list draws for its next page while it loads: enough to read as the list
 * going on, not a whole page.
 */
export const LOADING_MORE_ROWS = 3;

export interface InfiniteListEndProps extends Omit<MorePages, "isLoadingMore"> {
	/** Absent for a list that reveals rows it already holds, which never waits. */
	isLoadingMore?: boolean;
	/** "View earlier reviews". */
	moreLabel: string;
	/** "We could not load earlier reviews."; absent where nothing is requested, so nothing fails. */
	failedLabel?: string;
}

/**
 * The end of a paged list: it asks for more when it scrolls into view — the next page of a
 * `useInfiniteQuery` (through `MorePages`), or the next rows of a list held whole. The button stays,
 * because a keyboard needs it, and it is the only retry after a failure, so a failed page never
 * loads again on its own. TanStack Query ships no sentinel, so this pairs it with motion's
 * `useInView`. While the next page loads, the list itself draws placeholders in the shape of its
 * rows where those rows will appear; the end only says it is loading.
 */
export function InfiniteListEnd({
	hasMore,
	isLoadingMore = false,
	isRefreshing = false,
	loadMoreError,
	onLoadMore,
	moreLabel,
	failedLabel,
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
		<div tabIndex={-1} data-list-end className="focus-visible:outline-hidden">
			{(hasMore || failed) && (
				<div ref={end} className="flex flex-col gap-2.5">
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
