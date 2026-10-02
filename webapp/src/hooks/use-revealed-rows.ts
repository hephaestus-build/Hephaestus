import { useState } from "react";

import type { MorePages } from "@/runtime/tanstack-query/infinite-list";

/**
 * A list that arrived whole, shown a page at a time as its reader reaches the end, in the shape an
 * infinite query hands a list (`infiniteListState`), so one table draws both. Nothing is fetched, so
 * a page never fails and never waits.
 */
export function useRevealedRows<TRow>(
	rows: readonly TRow[],
	pageSize: number,
): { shown: readonly TRow[] } & MorePages {
	const [count, setCount] = useState(pageSize);
	return {
		shown: rows.slice(0, count),
		hasMore: count < rows.length,
		isLoadingMore: false,
		onLoadMore: () => setCount((shown) => shown + pageSize),
	};
}
