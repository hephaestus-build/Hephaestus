import {
	columnFilteringFeature,
	columnVisibilityFeature,
	createFilteredRowModel,
	createPaginatedRowModel,
	createSortedRowModel,
	globalFilteringFeature,
	rowPaginationFeature,
	rowSortingFeature,
	sortFn_alphanumeric,
	sortFn_datetime,
	sortFn_text,
	tableFeatures,
} from "@tanstack/react-table";

/**
 * The feature set the admin tables are built from. Beyond the always-present core row, column and
 * header APIs everything is opt-in, so this list is what a table here can do. `columnFilteringFeature`
 * is registered although no column filters: the types require it for `globalFilteringFeature` and
 * `filteredRowModel`.
 *
 * `sortFns` is the closed set `column.getAutoSortFn()` can name, not a guess at what a column might
 * want. Leave one out and that column degrades to a case-sensitive `>` comparison, saying so only in
 * a development warning.
 */
/** How a column lays out: a number column is right-aligned in tabular figures, as `TableCell numeric`. */
export interface DataTableColumnMeta {
	numeric?: boolean;
	/** Width and padding for the column's head and cells, such as `w-px` for a column that fits its content. */
	className?: string;
}

/** The type `columnDef.meta` takes; TanStack reads only the slot's type, never its value. */
const COLUMN_META: DataTableColumnMeta = {};

export const dataTableFeatures = tableFeatures({
	columnMeta: COLUMN_META,
	columnFilteringFeature,
	globalFilteringFeature,
	rowSortingFeature,
	rowPaginationFeature,
	columnVisibilityFeature,
	filteredRowModel: createFilteredRowModel(),
	sortedRowModel: createSortedRowModel(),
	paginatedRowModel: createPaginatedRowModel(),
	sortFns: {
		alphanumeric: sortFn_alphanumeric,
		datetime: sortFn_datetime,
		text: sortFn_text,
	},
});

export type DataTableFeatures = typeof dataTableFeatures;
