import type { InfiniteData } from "@tanstack/react-query";

import { type LoadState, type PanelState, queryLoadState } from "@/components/common/panel-state";

import { loadedPages } from "./spring-page";

/** An infinite list's paging, once its first page is in. */
export interface MorePages {
	hasMore: boolean;
	isLoadingMore: boolean;
	/**
	 * Why the last request for another page failed, while none is in flight. The pages already
	 * loaded stay, and `onLoadMore` asks again.
	 */
	loadMoreError?: unknown;
	onLoadMore: () => void;
}

/** The slice of an infinite query the state reads, so a hook hands its result in as it is. */
export interface InfiniteQueryLike<TPage> {
	isPending: boolean;
	isError: boolean;
	isFetchNextPageError: boolean;
	error: unknown;
	data: InfiniteData<TPage> | undefined;
	hasNextPage: boolean;
	isFetchingNextPage: boolean;
	refetch: () => unknown;
	fetchNextPage: () => unknown;
}

const READY: LoadState = { status: "ready" };

/**
 * One infinite query as a panel's state: loading or failed until its first page is in, then the
 * loaded pages shaped by `settled`. A later page that fails is not the panel's failure — TanStack
 * reports it as the query's error, but what was loaded stays on screen with the failure beside it.
 */
export function infiniteListState<TPage, TReady extends object>(
	query: InfiniteQueryLike<TPage>,
	settled: (pages: TPage[]) => TReady,
): PanelState<TReady & MorePages> {
	const state = query.isFetchNextPageError ? READY : queryLoadState(query);
	if (state.status !== "ready") {
		return state;
	}
	const more: MorePages = {
		hasMore: query.hasNextPage,
		isLoadingMore: query.isFetchingNextPage,
		onLoadMore: () => {
			void query.fetchNextPage();
		},
	};
	if (query.isFetchNextPageError && !query.isFetchingNextPage) {
		more.loadMoreError = query.error;
	}
	return { status: "ready", ...settled(loadedPages(query.data)), ...more };
}
