import { fn } from "storybook/test";

import type { MorePages, PagedListState } from "@/runtime/tanstack-query/infinite-list";

type LoadedList<TRow> = Extract<PagedListState<TRow>, { status: "ready" }>;

/**
 * A list with its first pages in, as a list route hands it to its screen. With no `more`, every row
 * is loaded and the list ends after the last one.
 */
export function loadedList<TRow>(rows: TRow[], more: Partial<MorePages> = {}): LoadedList<TRow> {
	return {
		status: "ready",
		rows,
		total: rows.length,
		hasMore: false,
		isLoadingMore: false,
		onLoadMore: fn(),
		...more,
	};
}

/**
 * What the server would answer for a filter, computed from every row a story has: the rows `keep`
 * selects, one page of them loaded, and more to load while any remain. Rows the list counts but has
 * not loaded stay counted.
 */
export function narrowedList<TRow>(
	list: PagedListState<TRow>,
	keep: (row: TRow) => boolean,
	pageSize: number,
): PagedListState<TRow> {
	if (list.status !== "ready") {
		return list;
	}
	const rows = list.rows.filter(keep);
	return {
		...list,
		rows: rows.slice(0, pageSize),
		total: rows.length + (list.total ?? list.rows.length) - list.rows.length,
		hasMore: list.hasMore || rows.length > pageSize,
	};
}
