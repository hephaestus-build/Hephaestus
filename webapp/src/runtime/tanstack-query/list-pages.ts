import type { InfiniteData } from "@tanstack/react-query";

/**
 * A numbered page (`OffsetPageDto*`). Every field is required, so a response of another shape fails
 * the type check instead of reading as a single page with no total.
 */
export interface OffsetPage<T> {
	content: T[];
	page: number;
	totalElements: number;
	totalPages: number;
}

export const offsetPageParams = {
	initialPageParam: 0,
	getNextPageParam: <T>(lastPage: OffsetPage<T>) =>
		lastPage.page + 1 < lastPage.totalPages ? lastPage.page + 1 : undefined,
};

/** Reads a cursor page (`CursorPageDto*`) forward until a page names no next cursor. */
export function nextCursorOf(lastPage: { nextCursor?: string }) {
	return lastPage.nextCursor;
}

/**
 * The generated client assembles its infinite query options behind a `@ts-ignore`, and what comes out
 * picks the `useInfiniteQuery` overload that declares `data` always defined. It is not — every
 * infinite query renders at least once with nothing loaded — so take pages through here, not `.pages`.
 */
export function loadedPages<TPage>(data: InfiniteData<TPage> | undefined): TPage[] {
	return data?.pages ?? [];
}
