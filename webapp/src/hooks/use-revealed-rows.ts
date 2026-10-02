import { useState } from "react";

/** The end of a list that arrived whole: whether rows are left, and how to show the next page. */
export interface RevealedMore {
	hasMore: boolean;
	onShowMore: () => void;
}

/**
 * A list that arrived whole, shown a page at a time: nothing is fetched, so a page never waits and
 * never fails, and the reader asks for the next one.
 */
export function useRevealedRows<TRow>(
	rows: readonly TRow[],
	pageSize: number,
): { shown: readonly TRow[] } & RevealedMore {
	const [count, setCount] = useState(pageSize);
	return {
		shown: rows.slice(0, count),
		hasMore: count < rows.length,
		onShowMore: () => setCount((shown) => shown + pageSize),
	};
}
