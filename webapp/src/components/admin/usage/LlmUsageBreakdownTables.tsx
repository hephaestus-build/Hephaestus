import { cn } from "cn";
import { Fragment } from "react";

import type { WorkspaceLlmUsageReport } from "@/api/types.gen";
import type { Purse } from "@/components/practice-vocabulary/purse-defs";
import {
	Table,
	TableBody,
	TableCaption,
	TableCell,
	TableFooter,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import {
	type AverageDigits,
	averageFractionDigits,
	formatAverageUsd,
	formatCostUsd,
} from "@/lib/money";

import {
	type TableSkeletonColumn,
	TableRowsSkeleton,
} from "@/components/admin/integrations/TableRowsSkeleton";
import { MoneyCell } from "./MoneyCell";
import { PurseHeading } from "./PurseHeading";
import {
	averageSpend,
	FIRST_COLUMN,
	formatTokens,
	formatUsageDay,
	JOB_TYPE_LABELS,
	type PurseSpend,
	spendOf,
	TOTAL_CELL,
	UNPRICED_RUNS,
	WIDE_ONLY,
} from "./usage-utils";

function sumBy<T>(rows: readonly T[], pick: (row: T) => number): number {
	return rows.reduce((total, row) => total + pick(row), 0);
}

/** A row of figures, the run count its averages divide by, and its runs with no price. */
interface PurseFigures extends PurseSpend {
	runs: number;
	unpriced: number;
}

/** A header that spans both header rows, after the purse groups. */
interface TrailingHead {
	label: string;
	className?: string;
}

interface PurseHeaderRowsProps {
	purses: readonly Purse[];
	/** The first column's header, which spans both header rows. */
	leading: string;
	trailing: readonly TrailingHead[];
}

/**
 * Two header rows: each purse's name over its own Spend and Avg per run, so one cell holds one
 * figure and the two purses are never read as one sum. A purse's name is a `col` header across both
 * of its columns, as `DataTableHeader` explains.
 */
function PurseHeaderRows({ purses, leading, trailing }: PurseHeaderRowsProps) {
	return (
		<TableHeader>
			<TableRow variant="static">
				<TableHead scope="col" rowSpan={2} className={cn(FIRST_COLUMN, "w-full")}>
					{leading}
				</TableHead>
				{purses.map((purse) => (
					<TableHead key={purse} scope="col" colSpan={2} className="text-center">
						<PurseHeading purse={purse} />
					</TableHead>
				))}
				{trailing.map(({ label, className }) => (
					<TableHead
						key={label}
						scope="col"
						rowSpan={2}
						numeric
						className={cn("text-right", className)}
					>
						{label}
					</TableHead>
				))}
			</TableRow>
			<TableRow variant="static">
				{purses.map((purse) => (
					<PurseLeafHeads key={purse} />
				))}
			</TableRow>
		</TableHeader>
	);
}

function PurseLeafHeads() {
	return (
		<>
			<TableHead scope="col" numeric className="text-right">
				Spend
			</TableHead>
			<TableHead scope="col" numeric className="text-right">
				Avg per run
			</TableHead>
		</>
	);
}

function averageOf(figures: PurseFigures, purse: Purse): number | null {
	return averageSpend(spendOf(figures, purse), figures.runs, figures.unpriced);
}

/** One precision per average column, its total included, so the decimal points line up. */
type AverageColumnDigits = Record<Purse, AverageDigits>;

function averageColumnDigits(column: readonly PurseFigures[]): AverageColumnDigits {
	const digitsOf = (purse: Purse) =>
		averageFractionDigits(column.map((figures) => averageOf(figures, purse)));
	return { SHARED: digitsOf("SHARED"), OWN_PROVIDER: digitsOf("OWN_PROVIDER") };
}

interface PurseCellsProps {
	purses: readonly Purse[];
	figures: PurseFigures;
	digits: AverageColumnDigits;
	className?: string;
}

function PurseCells({ purses, figures, digits, className }: PurseCellsProps) {
	return purses.map((purse) => {
		const average = averageOf(figures, purse);
		return (
			<Fragment key={purse}>
				<TableCell numeric className={cn("text-right", className)}>
					<MoneyCell>{formatCostUsd(spendOf(figures, purse))}</MoneyCell>
				</TableCell>
				<TableCell numeric className={cn("text-right", className)}>
					<MoneyCell>
						{average === null ? null : formatAverageUsd(average, digits[purse])}
					</MoneyCell>
				</TableCell>
			</Fragment>
		);
	});
}

function EmptyRow({ columns }: { columns: number }) {
	return (
		<TableRow variant="static">
			<TableCell colSpan={columns} className="h-24 text-center text-muted-foreground">
				No runs
			</TableCell>
		</TableRow>
	);
}

const TOKEN_COLUMNS: TrailingHead[] = [
	{ label: "Input tokens", className: WIDE_ONLY },
	{ label: "Cache reads", className: WIDE_ONLY },
	{ label: "Cache writes", className: WIDE_ONLY },
	{ label: "Output tokens", className: WIDE_ONLY },
	{ label: "Calls", className: WIDE_ONLY },
];

function skeletonColumns(
	purses: readonly Purse[],
	perPurse: number,
	trailing: readonly TrailingHead[],
) {
	return [
		{ width: "w-32", className: FIRST_COLUMN },
		...purses.flatMap(() =>
			Array.from({ length: perPurse }, () => ({ width: "w-16", numeric: true })),
		),
		...trailing.map(({ className }) => ({ width: "w-12", numeric: true, className })),
	] satisfies TableSkeletonColumn[];
}

export interface LlmUsageByJobTypeTableProps {
	/** Absent while the report loads. */
	report?: WorkspaceLlmUsageReport;
	/** The purses this page shows, the same on every table of it. */
	purses: readonly Purse[];
}

/** Five run types at most, so no sorting: the order is the server's. */
export function LlmUsageByJobTypeTable({ report, purses }: LlmUsageByJobTypeTableProps) {
	const rows = report?.byJobType;
	const hasUnpriced = rows?.some((row) => row.unpricedEventCount > 0) === true;
	const trailing: TrailingHead[] = [
		{ label: "Runs" },
		...(hasUnpriced ? [UNPRICED_RUNS] : []),
		...TOKEN_COLUMNS,
	];
	const rowFigures = (rows ?? []).map((row) => ({
		...row,
		runs: row.events,
		unpriced: row.unpricedEventCount,
	}));
	const total =
		report == null || rows == null || rows.length < 2
			? null
			: {
					instanceTotalCostUsd: report.instanceTotalCostUsd,
					ownProviderTotalCostUsd: report.ownProviderTotalCostUsd,
					runs: sumBy(rows, (row) => row.events),
					unpriced: report.unpricedEventCount,
					inputTokens: sumBy(rows, (row) => row.inputTokens),
					cacheReadTokens: sumBy(rows, (row) => row.cacheReadTokens),
					cacheWriteTokens: sumBy(rows, (row) => row.cacheWriteTokens),
					outputTokens: sumBy(rows, (row) => row.outputTokens),
					calls: sumBy(rows, (row) => row.totalCalls),
				};
	const digits = averageColumnDigits(total == null ? rowFigures : [...rowFigures, total]);

	return (
		<Table bordered>
			<TableCaption className="sr-only">AI spend by run type</TableCaption>
			<PurseHeaderRows purses={purses} leading="Run type" trailing={trailing} />
			{rows == null ? (
				<TableRowsSkeleton rows={3} columns={skeletonColumns(purses, 2, trailing)} />
			) : (
				<TableBody>
					{rows.length === 0 && <EmptyRow columns={1 + purses.length * 2 + trailing.length} />}
					{rowFigures.map((row) => (
						<TableRow key={row.jobType} variant="static">
							<TableCell className={cn(FIRST_COLUMN, "font-medium")}>
								{JOB_TYPE_LABELS[row.jobType]}
							</TableCell>
							<PurseCells purses={purses} figures={row} digits={digits} />
							<TableCell numeric className="text-right">
								{row.events.toLocaleString()}
							</TableCell>
							{hasUnpriced && (
								<TableCell numeric className="text-right">
									{row.unpricedEventCount.toLocaleString()}
								</TableCell>
							)}
							<TableCell numeric className={cn("text-right", WIDE_ONLY)}>
								{formatTokens(row.inputTokens)}
							</TableCell>
							<TableCell numeric className={cn("text-right", WIDE_ONLY)}>
								{formatTokens(row.cacheReadTokens)}
							</TableCell>
							<TableCell numeric className={cn("text-right", WIDE_ONLY)}>
								{formatTokens(row.cacheWriteTokens)}
							</TableCell>
							<TableCell numeric className={cn("text-right", WIDE_ONLY)}>
								{formatTokens(row.outputTokens)}
							</TableCell>
							<TableCell numeric className={cn("text-right", WIDE_ONLY)}>
								{row.totalCalls.toLocaleString()}
							</TableCell>
						</TableRow>
					))}
				</TableBody>
			)}
			{total != null && (
				<TableFooter>
					<TableRow variant="static">
						<TotalHead />
						<PurseCells purses={purses} figures={total} digits={digits} className={TOTAL_CELL} />
						<TableCell numeric className={cn("text-right", TOTAL_CELL)}>
							{total.runs.toLocaleString()}
						</TableCell>
						{hasUnpriced && (
							<TableCell numeric className={cn("text-right", TOTAL_CELL)}>
								{total.unpriced.toLocaleString()}
							</TableCell>
						)}
						<TableCell numeric className={cn("text-right", WIDE_ONLY, TOTAL_CELL)}>
							{formatTokens(total.inputTokens)}
						</TableCell>
						<TableCell numeric className={cn("text-right", WIDE_ONLY, TOTAL_CELL)}>
							{formatTokens(total.cacheReadTokens)}
						</TableCell>
						<TableCell numeric className={cn("text-right", WIDE_ONLY, TOTAL_CELL)}>
							{formatTokens(total.cacheWriteTokens)}
						</TableCell>
						<TableCell numeric className={cn("text-right", WIDE_ONLY, TOTAL_CELL)}>
							{formatTokens(total.outputTokens)}
						</TableCell>
						<TableCell numeric className={cn("text-right", WIDE_ONLY, TOTAL_CELL)}>
							{total.calls.toLocaleString()}
						</TableCell>
					</TableRow>
				</TableFooter>
			)}
		</Table>
	);
}

/**
 * The money and the runs with no price are the report's own month totals, never a re-addition of the
 * rows: one run can have usage on two days. The other counts are integers and add up exactly.
 */
function TotalHead() {
	return (
		<TableHead scope="row" className={cn(FIRST_COLUMN, TOTAL_CELL)}>
			Total
		</TableHead>
	);
}

export interface LlmUsageByDayTableProps {
	/** Absent while the report loads. */
	report?: WorkspaceLlmUsageReport;
	/** The purses this page shows, the same on every table of it. */
	purses: readonly Purse[];
}

/**
 * 31 days at most, so no sorting or paging: the order is the calendar's. Spend only: a daily
 * average per purse answers no question that the run types do not.
 */
export function LlmUsageByDayTable({ report, purses }: LlmUsageByDayTableProps) {
	const rows = report?.byDay;
	const hasUnpriced = rows?.some((row) => row.unpricedEventCount > 0) === true;
	const trailing: TrailingHead[] = [{ label: "Runs" }, ...(hasUnpriced ? [UNPRICED_RUNS] : [])];
	const total =
		report == null || rows == null || rows.length < 2
			? null
			: {
					instanceTotalCostUsd: report.instanceTotalCostUsd,
					ownProviderTotalCostUsd: report.ownProviderTotalCostUsd,
					runs: sumBy(rows, (row) => row.events),
					unpriced: report.unpricedEventCount,
				};

	return (
		<Table bordered>
			<TableCaption className="sr-only">AI spend by day</TableCaption>
			<TableHeader>
				<TableRow variant="static">
					<TableHead scope="col" className={cn(FIRST_COLUMN, "w-full")}>
						Day
					</TableHead>
					{purses.map((purse) => (
						<TableHead key={purse} scope="col" numeric className="text-right">
							<PurseHeading purse={purse} />
						</TableHead>
					))}
					{trailing.map(({ label, className }) => (
						<TableHead key={label} scope="col" numeric className={cn("text-right", className)}>
							{label}
						</TableHead>
					))}
				</TableRow>
			</TableHeader>
			{rows == null ? (
				<TableRowsSkeleton rows={3} columns={skeletonColumns(purses, 1, trailing)} />
			) : (
				<TableBody>
					{rows.length === 0 && <EmptyRow columns={1 + purses.length + trailing.length} />}
					{rows.map((row) => (
						<TableRow key={String(row.day)} variant="static">
							<TableCell className={cn(FIRST_COLUMN, "font-medium")}>
								{formatUsageDay(row.day)}
							</TableCell>
							<DaySpendCells purses={purses} figures={row} />
							<TableCell numeric className="text-right">
								{row.events.toLocaleString()}
							</TableCell>
							{hasUnpriced && (
								<TableCell numeric className="text-right">
									{row.unpricedEventCount.toLocaleString()}
								</TableCell>
							)}
						</TableRow>
					))}
				</TableBody>
			)}
			{total != null && (
				<TableFooter>
					<TableRow variant="static">
						<TotalHead />
						<DaySpendCells purses={purses} figures={total} className={TOTAL_CELL} />
						<TableCell numeric className={cn("text-right", TOTAL_CELL)}>
							{total.runs.toLocaleString()}
						</TableCell>
						{hasUnpriced && (
							<TableCell numeric className={cn("text-right", TOTAL_CELL)}>
								{total.unpriced.toLocaleString()}
							</TableCell>
						)}
					</TableRow>
				</TableFooter>
			)}
		</Table>
	);
}

function DaySpendCells({
	purses,
	figures,
	className,
}: {
	purses: readonly Purse[];
	figures: PurseSpend;
	className?: string;
}) {
	return purses.map((purse) => (
		<TableCell key={purse} numeric className={cn("text-right", className)}>
			<MoneyCell>{formatCostUsd(spendOf(figures, purse))}</MoneyCell>
		</TableCell>
	));
}
