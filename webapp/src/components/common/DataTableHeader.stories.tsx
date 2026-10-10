import type { Meta, StoryObj } from "@storybook/react-vite";
import { createColumnHelper, FlexRender, useTable } from "@tanstack/react-table";
import { expect, within } from "storybook/test";

import { type DataTableFeatures, dataTableFeatures } from "@/components/common/data-table";
import { Table, TableBody, TableCaption, TableCell, TableRow } from "@/components/ui/table";

import { DataTableHeader } from "./DataTableHeader";

interface Spend {
	name: string;
	shared: number;
	sharedAverage: number;
	runs: number;
}

const columnHelper = createColumnHelper<DataTableFeatures, Spend>();

const columns = columnHelper.columns([
	columnHelper.accessor("name", { header: "Practice" }),
	columnHelper.group({
		id: "shared-models",
		header: "Shared models",
		columns: columnHelper.columns([
			columnHelper.accessor("shared", {
				header: "Spend",
				sortDescFirst: true,
				meta: { numeric: true },
			}),
			columnHelper.accessor("sharedAverage", {
				header: "Avg per review",
				sortDescFirst: true,
				meta: { numeric: true },
			}),
		]),
	}),
	columnHelper.accessor("runs", {
		header: "Reviews",
		sortDescFirst: true,
		meta: { numeric: true },
	}),
]);

const data: Spend[] = [
	{ name: "Comments explain why", shared: 0.36, sharedAverage: 0.02, runs: 18 },
	{ name: "Names say what they hold", shared: 0.12, sharedAverage: 0.01, runs: 12 },
	{ name: "Small pull requests", shared: 0.6, sharedAverage: 0.2, runs: 3 },
];

function GroupedTable() {
	const table = useTable({
		features: dataTableFeatures,
		data,
		columns,
		initialState: { sorting: [{ id: "shared", desc: true }] },
	});
	return (
		<Table bordered>
			<TableCaption className="sr-only">Spend by practice</TableCaption>
			<DataTableHeader table={table} />
			<TableBody>
				{table.getSortedRowModel().rows.map((row) => (
					<TableRow key={row.id}>
						{row.getVisibleCells().map((cell) => (
							<TableCell
								key={cell.id}
								numeric={cell.column.columnDef.meta?.numeric}
								className={cell.column.columnDef.meta?.numeric === true ? "text-right" : undefined}
							>
								<FlexRender cell={cell} />
							</TableCell>
						))}
					</TableRow>
				))}
			</TableBody>
		</Table>
	);
}

/**
 * The header of a TanStack table with a grouped column. The group spans its leaves as a `col` header;
 * a leaf with no group spans both header rows instead of leaving an empty cell above itself. Only a
 * leaf sorts, so only a leaf carries `aria-sort`. A numeric leaf is right-aligned, its arrow before
 * the label. The first press sorts in the column's own first direction (`sortDescFirst`), and the
 * next press reverses it.
 *
 * The stories render a small table around the header, since the header needs a live TanStack table;
 * that harness is not the component's API, so this file publishes no Docs page.
 */
const meta = {
	parameters: { layout: "padded" },
	render: () => <GroupedTable />,
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

export const GroupedColumns: Story = {
	play: async ({ canvas, userEvent }) => {
		const table = within(await canvas.findByRole("table", { name: "Spend by practice" }));
		const group = table.getByRole("columnheader", { name: "Shared models" });
		await expect(group).toHaveAttribute("colspan", "2");
		await expect(group).toHaveAttribute("scope", "col");
		await expect(group).not.toHaveAttribute("aria-sort");

		const practice = table.getByRole("columnheader", { name: "Practice" });
		await expect(practice).toHaveAttribute("rowspan", "2");
		await expect(practice).toHaveAttribute("aria-sort", "none");
		// Two header rows, and no empty cell left where a leaf spans both.
		await expect(
			table
				.getAllByRole("row")
				.slice(0, 2)
				.map((row) => within(row).getAllByRole("columnheader").length),
		).toStrictEqual([3, 2]);

		const spend = table.getByRole("columnheader", { name: "Spend" });
		await expect(spend).toHaveAttribute("aria-sort", "descending");
		await expect(spend).toHaveClass("text-right");

		const reviews = table.getByRole("columnheader", { name: "Reviews" });
		await userEvent.click(within(reviews).getByRole("button", { name: "Reviews" }));
		// A figure sorts biggest first on the first press, and the next press reverses it.
		await expect(reviews).toHaveAttribute("aria-sort", "descending");
		await expect(spend).toHaveAttribute("aria-sort", "none");
		await expect(table.getAllByRole("row")[2]).toHaveTextContent(/^Comments explain why/u);
		await userEvent.click(within(reviews).getByRole("button", { name: "Reviews" }));
		await expect(reviews).toHaveAttribute("aria-sort", "ascending");

		await userEvent.click(within(practice).getByRole("button", { name: "Practice" }));
		// A name sorts from A on the first press.
		await expect(practice).toHaveAttribute("aria-sort", "ascending");
	},
};
