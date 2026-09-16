import type { InfiniteData } from "@tanstack/react-query";

export interface SpringPage<T> {
	content?: T[];
	number?: number;
	last?: boolean;
	totalElements?: number;
}

export const springPageParams = {
	initialPageParam: 0,
	getNextPageParam: <T>(lastPage: SpringPage<T>) =>
		lastPage.last === true ? undefined : (lastPage.number ?? 0) + 1,
};

/**
 * The generated client assembles its infinite query options behind a `@ts-ignore`, and what comes out
 * picks the `useInfiniteQuery` overload that declares `data` always defined. It is not — every
 * infinite query renders at least once with nothing loaded — so take pages through here, not `.pages`.
 */
export function loadedPages<TPage>(data: InfiniteData<TPage> | undefined): TPage[] {
	return data?.pages ?? [];
}
