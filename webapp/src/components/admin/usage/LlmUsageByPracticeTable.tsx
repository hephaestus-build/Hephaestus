import { Link } from "@tanstack/react-router";
import { createColumnHelper, FlexRender, type SortingState, useTable } from "@tanstack/react-table";
// oxlint-disable-next-line no-restricted-imports -- TanStack Table keys its column model on the identity of `columns`, which the compiler memoises as an optimisation rather than a promise.
import { useMemo, useState } from "react";

import { cn } from "cn";
import type { LlmUsageByPractice, LlmUsagePracticeScriptTotal } from "@/api/types.gen";
import { practiceSetupLevel } from "@/components/admin/practices/practice-search";
import { type DataTableFeatures, dataTableFeatures } from "@/components/common/data-table";
import { DataTableHeader } from "@/components/common/DataTableHeader";
import { InlineLink } from "@/components/common/InlineLink";
import { TableRowsSkeleton } from "@/components/common/TableRowsSkeleton";
import { detailSearch } from "@/components/layout/detail-drawer/detail-stack";
import { isPracticeScriptPurpose } from "@/components/practice-vocabulary/agent-purpose-defs";
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
import { formatNumber } from "@/lib/numbers";

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
	report?: { byPractice: LlmUsageByPractice[]; practiceScriptTotal: LlmUsagePracticeScriptTotal };
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

// Per-practice spend is part of review spend; use server totals to avoid counting shared reviews twice.
export function LlmUsageByPracticeTable({
	report,
	purses,
	workspaceSlug,
}: LlmUsageByPracticeTableProps) {
	const rows = report?.byPractice;
	const total = rows != null && rows.length > 1 ? report?.practiceScriptTotal : undefined;
	const hasUnpriced = rows?.some((row) => row.unpricedEventCount > 0) === true;
	const [sorting, setSorting] = useState<SortingState>([{ id: "SHARED-spend", desc: true }]);

	// TanStack Table caches the column model by identity; memoize every input that defines it.
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
					cell: ({ getValue }) => formatNumber(getValue()),
					footer: () => (total == null ? undefined : formatNumber(total.reviews)),
					sortDescFirst: true,
					meta: NUMERIC,
				}),
				...(hasUnpriced
					? [
							columnHelper.accessor("unpricedEventCount", {
								header: UNPRICED_RUNS.label,
								cell: ({ getValue }) => formatNumber(getValue()),
								footer: () => (total == null ? undefined : formatNumber(total.unpricedEventCount)),
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
					cell: ({ getValue }) => formatNumber(getValue()),
					footer: () => (total == null ? undefined : formatNumber(total.calls)),
					sortDescFirst: true,
					meta: NUMERIC_WIDE_ONLY,
				}),
			]),
		[rows, total, hasUnpriced, purses, workspaceSlug],
	);

	const data = useMemo(() => rows ?? [], [rows]);

	const table = useTable({
		features: dataTableFeatures,
		data,
		columns,
		onSortingChange: setSorting,
		state: { sorting },
	});
	const [leafFooters] = table.getFooterGroups();
	// The default row model pages; use the sorted model for this unpaged table.
	const sortedRows = table.getSortedRowModel().rows;
	const orderedRows = [
		...sortedRows.filter((row) => row.original.practiceSlug !== undefined),
		...sortedRows.filter((row) => row.original.practiceSlug === undefined),
	];

	return (
		<Table bordered>
			<TableCaption className="sr-only">
				Spend on models for practice scripts, by practice
			</TableCaption>
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
	figures: LlmUsageByPractice | LlmUsagePracticeScriptTotal,
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
			{row.purposes.filter(isPracticeScriptPurpose).map((purpose) => (
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
