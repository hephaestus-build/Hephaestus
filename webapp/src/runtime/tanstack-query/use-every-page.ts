import { useEffect } from "react";

/**
 * Reads an infinite list to its end by itself, for a choice or a count that needs every row.
 * It waits for any fetch, not only a next page: a next-page fetch during a refetch cancels the refetch.
 */
export function useEveryPage(query: {
	hasNextPage: boolean;
	isFetching: boolean;
	isError: boolean;
	fetchNextPage: () => unknown;
}) {
	const { hasNextPage, isFetching, isError, fetchNextPage } = query;
	useEffect(() => {
		if (hasNextPage && !isFetching && !isError) {
			void fetchNextPage();
		}
	}, [hasNextPage, isFetching, isError, fetchNextPage]);
}
