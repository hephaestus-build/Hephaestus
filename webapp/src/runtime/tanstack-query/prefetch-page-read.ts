import {
	noop,
	type QueryClient,
	type QueryExecuteOptions,
	type QueryKey,
} from "@tanstack/react-query";

/**
 * Starts one of a page's reads from its route loader, before the page mounts. Hover intent runs the
 * loader, so the data is often in the cache by the time the reader clicks, and the page opens whole
 * instead of showing its skeleton for a round trip. A read that is still fresh is not read again.
 *
 * The loader does not wait. A slow read keeps the page's own loading view: the router would otherwise
 * hold the old page, then show a generic page skeleton instead of this page's title and regions.
 *
 * A read that fails with no data and no page watching it is removed afterwards, so the page reads it
 * again with its own retries and error state rather than opening on an old failure.
 *
 * A route loader loads with the entry chunk unless the route splits it off. A route that calls this
 * sets `codeSplitGroupings` with `["loader"]` as a group of its own, so its query options load beside
 * its page's code on hover instead of growing every page's first load.
 */
export function prefetchPageRead<TQueryFnData, TError, TQueryKey extends QueryKey>(
	queryClient: QueryClient,
	read: QueryExecuteOptions<TQueryFnData, TError, TQueryFnData, TQueryFnData, TQueryKey>,
): void {
	void queryClient
		.query(read)
		.catch(noop)
		.then(() => {
			const query = queryClient.getQueryCache().find({ queryKey: read.queryKey, exact: true });
			if (
				query?.state.status === "error" &&
				query.state.data === undefined &&
				query.getObserversCount() === 0
			) {
				queryClient.removeQueries({ queryKey: read.queryKey, exact: true });
			}
		});
}
