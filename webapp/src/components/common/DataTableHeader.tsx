import { FlexRender, type Column, type RowData, type Table } from "@tanstack/react-table";

import { cn } from "cn";
import type { DataTableFeatures } from "@/components/common/data-table";
import { SortButton } from "@/components/common/SortButton";
import { TableHead, TableHeader, TableRow } from "@/components/ui/table";

/**
 * The header rows of a TanStack table: the `<th>`s, the sort control on every column that can sort,
 * and the `aria-sort` that makes the state legible without the icon.
 *
 * A grouped column spans its leaves with `colSpan`, as a `col` header: `colgroup` scope needs a
 * `<colgroup>` element to anchor it. A leaf with no group above it is one cell that spans every
 * header row: TanStack reports the span on the top placeholder and `rowSpan` 0 on each cell that it
 * covers, so those are skipped. Only a leaf sorts, so only a leaf carries `aria-sort`. The first
 * click sorts in the column's `getFirstSortDir()`, which a figure column sets with `sortDescFirst`.
 *
 * The sort state is read here rather than inside a column's `header`, and that is load-bearing.
 * `header.getContext()` is memoised on the identity of `columns`, so a header renderer is called
 * with the same props object on every render and the compiler caches its output — a `getIsSorted()`
 * read inside one is stale from the first click onwards. A column declares a label; the state
 * belongs to whoever re-renders when it changes.
 */
export function DataTableHeader<TData extends RowData>({
	table,
}: {
	table: Table<DataTableFeatures, TData>;
}) {
	return (
		<TableHeader>
			{table.getHeaderGroups().map((headerGroup) => (
				<TableRow key={headerGroup.id} variant="static">
					{headerGroup.headers
						.filter((header) => header.rowSpan > 0)
						.map((header) => {
							const { column } = header;
							const isLeaf = column.columns.length === 0;
							const { meta } = column.columnDef;
							const numeric = meta?.numeric === true;
							const sorted = column.getIsSorted();
							const label = <FlexRender header={header} />;

							return (
								<TableHead
									key={header.id}
									scope="col"
									colSpan={header.colSpan > 1 ? header.colSpan : undefined}
									rowSpan={header.rowSpan > 1 ? header.rowSpan : undefined}
									aria-sort={isLeaf ? ariaSort(column) : undefined}
									numeric={numeric}
									className={cn(numeric && "text-right", !isLeaf && "text-center", meta?.className)}
								>
									{isLeaf && column.getCanSort() ? (
										<SortButton
											sorted={sorted}
											// A right-aligned number keeps its arrow on the inside, next to the column.
											reverse={numeric}
											onToggle={() =>
												column.toggleSorting(
													sorted === false ? column.getFirstSortDir() === "desc" : sorted === "asc",
												)
											}
										>
											{label}
										</SortButton>
									) : (
										label
									)}
								</TableHead>
							);
						})}
				</TableRow>
			))}
		</TableHeader>
	);
}

function ariaSort<TData extends RowData>(
	column: Column<DataTableFeatures, TData>,
): "ascending" | "descending" | "none" | undefined {
	// Undefined rather than "none": "none" advertises a sort control that is not there.
	if (!column.getCanSort()) {
		return undefined;
	}
	const sorted = column.getIsSorted();
	if (sorted === false) {
		return "none";
	}
	return sorted === "asc" ? "ascending" : "descending";
}
