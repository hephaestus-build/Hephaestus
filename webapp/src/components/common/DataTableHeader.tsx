import { FlexRender, type Column, type RowData, type Table } from "@tanstack/react-table";

import type { DataTableFeatures } from "@/components/common/data-table";
import { SortButton } from "@/components/common/SortButton";
import { TableHead, TableHeader, TableRow } from "@/components/ui/table";

/**
 * The header row of a TanStack table: the `<th>`s, the sort control on every column that can sort,
 * and the `aria-sort` that makes the state legible without the icon.
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
				<TableRow key={headerGroup.id}>
					{headerGroup.headers.map((header) => {
						const { column } = header;
						const sorted = column.getIsSorted();
						const label = header.isPlaceholder ? null : <FlexRender header={header} />;

						return (
							<TableHead key={header.id} aria-sort={ariaSort(column)}>
								{column.getCanSort() ? (
									<SortButton
										sorted={sorted}
										onToggle={() => column.toggleSorting(sorted === "asc")}
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
	if (!sorted) {
		return "none";
	}
	return sorted === "asc" ? "ascending" : "descending";
}
