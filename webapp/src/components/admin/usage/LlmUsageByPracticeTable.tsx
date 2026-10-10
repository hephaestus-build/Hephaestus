import { Link } from "@tanstack/react-router";
import { createColumnHelper, FlexRender, type SortingState, useTable } from "@tanstack/react-table";
// oxlint-disable-next-line no-restricted-imports -- TanStack Table keys its column model on the identity of `columns`, which the compiler memoises as an optimisation rather than a promise.
import { useMemo, useState } from "react";

import { cn } from "cn";
import type { LlmUsageByPractice, LlmUsagePrecomputeTotal } from "@/api/types.gen";
import { TableRowsSkeleton } from "@/components/admin/integrations/TableRowsSkeleton";
import { practiceSetupLevel } from "@/components/admin/practices/practice-search";
import { type DataTableFeatures, dataTableFeatures } from "@/components/common/data-table";
import { DataTableHeader } from "@/components/common/DataTableHeader";
import { InlineLink } from "@/components/common/InlineLink";
import { detailSearch } from "@/components/layout/detail-drawer/detail-stack";
import { isPrecomputePurpose } from "@/components/practice-vocabulary/agent-purpose-defs";
import { ModelKindMark } from "@/components/practice-vocabulary/ModelKindMark";
import type { Purse } from "@/components/practice-vocabulary/purse-defs";
import {
	Table,
	TableBody,
	TableCaption,
	TableCell,
	TableFooter,
	TableHead,
	TableRow,
} from "@/components/ui/table";
import {
	type AverageDigits,
	averageFractionDigits,
	formatAverageUsd,
	formatCostUsd,
} from "@/lib/money";

import { MoneyCell } from "./MoneyCell";
import { PurseHeading, PurseLeaf } from "./PurseHeading";
import {
	averageSpend,
	FIRST_COLUMN,
	formatTokens,
	spendOf,
	TOTAL_CELL,
	UNPRICED_RUNS,
	WIDE_ONLY,
} from "./usage-utils";

export interface LlmUsageByPracticeTableProps {
	/** Absent while the report loads. */
	report?: { byPractice: LlmUsageByPractice[]; precomputeTotal: LlmUsagePrecomputeTotal };
	/** The purses this page shows, the same on every table of it: `pursesOf`, one array per answer. */
	purses: readonly Purse[];
	/**
	 * Links each practice to Practice setup in this workspace. The instance console leaves it out:
	 * an instance admin does not have to belong to the workspace.
	 */
	workspaceSlug?: string;
}

const columnHelper = createColumnHelper<DataTableFeatures, LlmUsageByPractice>();

const NUMERIC = { numeric: true };
const NUMERIC_WIDE_ONLY = { numeric: true, className: WIDE_ONLY };

/**
 * Decision, embedding and reranking calls of precompute scripts, one row per practice, most shared
 * spend first. The footer is the server's `precomputeTotal`: one review runs several practices, so its
 * reviews, with or without a price, do not add up from the rows, and money is never re-added here.
 */
export function LlmUsageByPracticeTable({
	report,
	purses,
	workspaceSlug,
}: LlmUsageByPracticeTableProps) {
	const rows = report?.byPractice;
	const total = rows != null && rows.length > 1 ? report?.precomputeTotal : undefined;
	const hasUnpriced = rows?.some((row) => row.unpricedEventCount > 0) === true;
	const [sorting, setSorting] = useState<SortingState>([{ id: "SHARED-spend", desc: true }]);

	// TanStack Table caches its column model, and every row model derived from it, against this
	// array's identity. Its inputs are the report's own arrays, the page's purses and two flags, never
	// a fresh array per render.
	const columns = useMemo(
		() =>
			columnHelper.columns([
				columnHelper.accessor(practiceSortName, {
					id: "practice",
					header: "Practice",
					cell: ({ row }) => <PracticeName row={row.original} workspaceSlug={workspaceSlug} />,
					footer: "Total",
					meta: { className: cn(FIRST_COLUMN, "w-full min-w-32 whitespace-normal sm:min-w-44") },
				}),
				columnHelper.display({
					id: "models",
					header: "Models",
					cell: ({ row }) => <ModelKinds row={row.original} />,
				}),
				...purses.map((purse) => {
					// One precision for the whole column, its total included, so the decimal points line up.
					const digits = averageFractionDigits([
						...(rows ?? []).map((row) => averageOf(row, purse)),
						total == null ? null : averageOf(total, purse),
					]);
					return columnHelper.group({
						id: purse,
						header: () => <PurseHeading purse={purse} />,
						columns: columnHelper.columns([
							columnHelper.accessor((row) => spendOf(row, purse), {
								id: `${purse}-spend`,
								header: () => <PurseLeaf purse={purse}>Spend</PurseLeaf>,
								cell: ({ getValue }) => <MoneyCell>{formatCostUsd(getValue())}</MoneyCell>,
								footer: () =>
									total == null ? null : (
										<MoneyCell>{formatCostUsd(spendOf(total, purse))}</MoneyCell>
									),
								sortDescFirst: true,
								meta: NUMERIC,
							}),
							// A row with nothing to average sorts below every row that has an average.
							columnHelper.accessor((row) => averageOf(row, purse) ?? -1, {
								id: `${purse}-average`,
								header: () => <PurseLeaf purse={purse}>Avg per review</PurseLeaf>,
								cell: ({ row }) => (
									<AverageCell average={averageOf(row.original, purse)} digits={digits} />
								),
								footer: () =>
									total == null ? null : (
										<AverageCell average={averageOf(total, purse)} digits={digits} />
									),
								sortDescFirst: true,
								meta: NUMERIC,
							}),
						]),
					});
				}),
				columnHelper.accessor("reviews", {
					header: "Reviews",
					cell: ({ getValue }) => getValue().toLocaleString(),
					footer: () => total?.reviews.toLocaleString(),
					sortDescFirst: true,
					meta: NUMERIC,
				}),
				...(hasUnpriced
					? [
							columnHelper.accessor("unpricedEventCount", {
								header: UNPRICED_RUNS.label,
								cell: ({ getValue }) => getValue().toLocaleString(),
								footer: () => total?.unpricedEventCount.toLocaleString(),
								sortDescFirst: true,
								meta: { numeric: true, className: UNPRICED_RUNS.className },
							}),
						]
					: []),
				columnHelper.accessor("inputTokens", {
					header: "Input tokens",
					cell: ({ getValue }) => formatTokens(getValue()),
					footer: () => formatTokens(total?.inputTokens),
					sortDescFirst: true,
					meta: NUMERIC_WIDE_ONLY,
				}),
				columnHelper.accessor("outputTokens", {
					header: "Output tokens",
					cell: ({ getValue }) => formatTokens(getValue()),
					footer: () => formatTokens(total?.outputTokens),
					sortDescFirst: true,
					meta: NUMERIC_WIDE_ONLY,
				}),
				columnHelper.accessor("calls", {
					header: "Calls",
					cell: ({ getValue }) => getValue().toLocaleString(),
					footer: () => total?.calls.toLocaleString(),
					sortDescFirst: true,
					meta: NUMERIC_WIDE_ONLY,
				}),
			]),
		[rows, total, hasUnpriced, purses, workspaceSlug],
	);

	// The table's `data`, keyed the same way `columns` is.
	const data = useMemo(() => rows ?? [], [rows]);

	const table = useTable({
		features: dataTableFeatures,
		data,
		columns,
		onSortingChange: setSorting,
		state: { sorting },
	});
	const [leafFooters] = table.getFooterGroups();
	// The sorted model, not `getRowModel()`: the feature set pages by default, and this table never
	// pages. *Not attributed* is not a practice, so it stays last, above the total, whatever the sort.
	const sortedRows = table.getSortedRowModel().rows;
	const orderedRows = [
		...sortedRows.filter((row) => row.original.practiceSlug !== undefined),
		...sortedRows.filter((row) => row.original.practiceSlug === undefined),
	];

	return (
		<Table bordered>
			<TableCaption className="sr-only">Precompute model spend by practice</TableCaption>
			<DataTableHeader table={table} />
			{rows == null ? (
				<TableRowsSkeleton
					rows={3}
					columns={table.getVisibleLeafColumns().map((column) => ({
						width: column.id === "practice" ? "w-40" : "w-14",
						numeric: column.columnDef.meta?.numeric,
						className: column.columnDef.meta?.className,
					}))}
				/>
			) : (
				<TableBody>
					{orderedRows.map((row) => (
						<TableRow key={row.id} variant="static">
							{row.getVisibleCells().map((cell) => {
								const { meta } = cell.column.columnDef;
								return (
									<TableCell
										key={cell.id}
										numeric={meta?.numeric}
										className={cn(meta?.numeric === true && "text-right", meta?.className)}
									>
										<FlexRender cell={cell} />
									</TableCell>
								);
							})}
						</TableRow>
					))}
				</TableBody>
			)}
			{total != null && leafFooters != null && (
				<TableFooter>
					<TableRow variant="static">
						{leafFooters.headers.map((footer) => {
							const { meta } = footer.column.columnDef;
							return footer.column.id === "practice" ? (
								<TableHead key={footer.id} scope="row" className={cn(meta?.className, TOTAL_CELL)}>
									<FlexRender footer={footer} />
								</TableHead>
							) : (
								<TableCell
									key={footer.id}
									numeric={meta?.numeric}
									className={cn(
										meta?.numeric === true && "text-right",
										meta?.className,
										TOTAL_CELL,
									)}
								>
									<FlexRender footer={footer} />
								</TableCell>
							);
						})}
					</TableRow>
				</TableFooter>
			)}
		</Table>
	);
}

function practiceSortName(row: LlmUsageByPractice): string {
	return row.practiceName ?? row.practiceSlug ?? "";
}

function averageOf(
	figures: LlmUsageByPractice | LlmUsagePrecomputeTotal,
	purse: Purse,
): number | null {
	return averageSpend(spendOf(figures, purse), figures.reviews, figures.unpricedEventCount);
}

function AverageCell({ average, digits }: { average: number | null; digits: AverageDigits }) {
	return <MoneyCell>{average === null ? null : formatAverageUsd(average, digits)}</MoneyCell>;
}

function ModelKinds({ row }: { row: LlmUsageByPractice }) {
	return (
		<span className="flex flex-col gap-1">
			{row.purposes.filter(isPrecomputePurpose).map((purpose) => (
				<ModelKindMark key={purpose} purpose={purpose} />
			))}
		</span>
	);
}

interface PracticeNameProps {
	row: LlmUsageByPractice;
	workspaceSlug?: string;
}

function PracticeName({ row, workspaceSlug }: PracticeNameProps) {
	const { practiceSlug, practiceName } = row;
	if (practiceSlug === undefined) {
		return <span className="text-muted-foreground">Not attributed</span>;
	}
	if (practiceName === undefined) {
		return (
			<>
				<div className="font-mono text-xs">{practiceSlug}</div>
				<div className="text-xs text-muted-foreground">Not a current practice</div>
			</>
		);
	}
	if (workspaceSlug === undefined) {
		return <span className="font-medium">{practiceName}</span>;
	}
	return (
		<InlineLink
			className="font-medium"
			render={
				<Link
					to="/w/$workspaceSlug/admin/practices"
					params={{ workspaceSlug }}
					search={detailSearch(practiceSetupLevel(practiceSlug))}
				/>
			}
		>
			{practiceName}
		</InlineLink>
	);
}
