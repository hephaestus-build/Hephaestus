import type { LlmUsageByJobType, WorkspaceLlmUsageReport } from "@/api/types.gen";
import { TableRowsSkeleton } from "@/components/admin/integrations/TableRowsSkeleton";
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
import { formatCostUsd, formatRateUsd } from "@/lib/money";
import { MoneyCell } from "./MoneyCell";

import { type Fx, FxSpendLine } from "./fx";
import { formatTokens, formatUsageDay, JOB_TYPE_LABELS } from "./usage-utils";

function sumBy<T>(rows: T[], pick: (row: T) => number): number {
	return rows.reduce((total, row) => total + pick(row), 0);
}

const JOB_TYPE_SKELETON_COLUMNS = [
	"w-32",
	"w-16",
	"w-16",
	"w-20",
	"w-16",
	"w-20",
	"w-20",
	"w-20",
	"w-20",
	"w-12",
	"w-12",
];
const DAY_SKELETON_COLUMNS = ["w-16", "w-16", "w-16", "w-16", "w-12"];

export interface LlmUsageByJobTypeTableProps {
	report?: WorkspaceLlmUsageReport;
	fx?: Fx;
}

export function LlmUsageByJobTypeTable({ report, fx }: LlmUsageByJobTypeTableProps) {
	const rows = report?.byJobType;
	const totals =
		report == null || rows == null || rows.length < 2
			? null
			: {
					priced: report.instanceTotalCostUsd,
					ownProvider: report.ownProviderTotalCostUsd,
					unpriced: sumBy(rows, (row) => row.unpricedEventCount),
					inputTokens: sumBy(rows, (row) => row.inputTokens),
					cacheReadTokens: sumBy(rows, (row) => row.cacheReadTokens),
					cacheWriteTokens: sumBy(rows, (row) => row.cacheWriteTokens),
					outputTokens: sumBy(rows, (row) => row.outputTokens),
					calls: sumBy(rows, (row) => row.totalCalls),
					events: sumBy(rows, (row) => row.events),
				};
	return (
		<Table bordered>
			<TableCaption className="sr-only">AI spend by run type</TableCaption>
			<TableHeader>
				<TableRow>
					<TableHead scope="col">Run type</TableHead>
					<TableHead scope="col" className="text-right">
						Shared models
					</TableHead>
					<TableHead scope="col" className="text-right">
						Your provider
					</TableHead>
					<TableHead scope="col" className="text-right">
						Avg per run
					</TableHead>
					<TableHead scope="col" className="text-right">
						No price set
					</TableHead>
					<TableHead scope="col" className="text-right">
						Input tokens
					</TableHead>
					<TableHead scope="col" className="text-right">
						Cache reads
					</TableHead>
					<TableHead scope="col" className="text-right">
						Cache writes
					</TableHead>
					<TableHead scope="col" className="text-right">
						Output tokens
					</TableHead>
					<TableHead scope="col" className="text-right">
						Calls
					</TableHead>
					<TableHead scope="col" className="text-right">
						Runs
					</TableHead>
				</TableRow>
			</TableHeader>
			{rows == null ? (
				<TableRowsSkeleton columns={JOB_TYPE_SKELETON_COLUMNS} rows={3} />
			) : (
				<TableBody>
					{rows.map((row) => (
						<TableRow key={row.jobType}>
							<TableCell className="font-medium">{JOB_TYPE_LABELS[row.jobType]}</TableCell>
							<TableCell numeric className="text-right">
								<MoneyCell>{formatCostUsd(row.instanceTotalCostUsd)}</MoneyCell>
							</TableCell>
							<TableCell numeric className="text-right">
								<MoneyCell>{formatCostUsd(row.ownProviderTotalCostUsd)}</MoneyCell>
							</TableCell>
							<TableCell numeric className="text-right">
								<AvgPerRun row={row} />
							</TableCell>
							<TableCell numeric className="text-right">
								{row.unpricedEventCount.toLocaleString()}
							</TableCell>
							<TableCell numeric className="text-right">
								{formatTokens(row.inputTokens)}
							</TableCell>
							<TableCell numeric className="text-right">
								{formatTokens(row.cacheReadTokens)}
							</TableCell>
							<TableCell numeric className="text-right">
								{formatTokens(row.cacheWriteTokens)}
							</TableCell>
							<TableCell numeric className="text-right">
								{formatTokens(row.outputTokens)}
							</TableCell>
							<TableCell numeric className="text-right">
								{row.totalCalls.toLocaleString()}
							</TableCell>
							<TableCell numeric className="text-right">
								{row.events.toLocaleString()}
							</TableCell>
						</TableRow>
					))}
				</TableBody>
			)}
			{totals != null && (
				<TableFooter>
					<TableRow>
						<TableCell>Total</TableCell>
						<TableCell numeric className="text-right">
							<MoneyCell>{formatCostUsd(totals.priced)}</MoneyCell>
							<FxSpendLine usd={totals.priced} fx={fx} />
						</TableCell>
						<TableCell numeric className="text-right">
							<MoneyCell>{formatCostUsd(totals.ownProvider)}</MoneyCell>
							<FxSpendLine usd={totals.ownProvider} fx={fx} />
						</TableCell>
						<TableCell className="text-right text-muted-foreground">—</TableCell>
						<TableCell numeric className="text-right">
							{totals.unpriced.toLocaleString()}
						</TableCell>
						<TableCell numeric className="text-right">
							{formatTokens(totals.inputTokens)}
						</TableCell>
						<TableCell numeric className="text-right">
							{formatTokens(totals.cacheReadTokens)}
						</TableCell>
						<TableCell numeric className="text-right">
							{formatTokens(totals.cacheWriteTokens)}
						</TableCell>
						<TableCell numeric className="text-right">
							{formatTokens(totals.outputTokens)}
						</TableCell>
						<TableCell numeric className="text-right">
							{totals.calls.toLocaleString()}
						</TableCell>
						<TableCell numeric className="text-right">
							{totals.events.toLocaleString()}
						</TableCell>
					</TableRow>
				</TableFooter>
			)}
		</Table>
	);
}

function AvgPerRun({ row }: { row: LlmUsageByJobType }) {
	const parts = [
		{ key: "shared", label: "shared models", total: row.instanceTotalCostUsd },
		{ key: "provider", label: "your provider", total: row.ownProviderTotalCostUsd },
	].filter((part) => part.total > 0);

	if (row.events <= 0 || parts.length === 0) {
		return <span className="text-muted-foreground">—</span>;
	}
	return (
		<div className="flex flex-col items-end">
			{parts.map((part) => (
				<span key={part.key}>
					{formatRateUsd(part.total / row.events)}
					{parts.length > 1 && (
						<>
							{" "}
							<span className="text-xs text-muted-foreground">{part.label}</span>
						</>
					)}
				</span>
			))}
		</div>
	);
}

export interface LlmUsageByDayTableProps {
	report?: WorkspaceLlmUsageReport;
	fx?: Fx;
}

export function LlmUsageByDayTable({ report, fx }: LlmUsageByDayTableProps) {
	const rows = report?.byDay;
	const totals =
		report == null || rows == null || rows.length < 2
			? null
			: {
					priced: report.instanceTotalCostUsd,
					ownProvider: report.ownProviderTotalCostUsd,
					unpriced: sumBy(rows, (row) => row.unpricedEventCount),
					events: sumBy(rows, (row) => row.events),
				};
	return (
		<Table bordered>
			<TableCaption className="sr-only">AI spend by day</TableCaption>
			<TableHeader>
				<TableRow>
					<TableHead scope="col">Day</TableHead>
					<TableHead scope="col" className="text-right">
						Shared models
					</TableHead>
					<TableHead scope="col" className="text-right">
						Your provider
					</TableHead>
					<TableHead scope="col" className="text-right">
						No price set
					</TableHead>
					<TableHead scope="col" className="text-right">
						Runs
					</TableHead>
				</TableRow>
			</TableHeader>
			{rows == null ? (
				<TableRowsSkeleton columns={DAY_SKELETON_COLUMNS} rows={3} />
			) : (
				<TableBody>
					{rows.map((row) => (
						<TableRow key={String(row.day)}>
							<TableCell className="font-medium">{formatUsageDay(row.day)}</TableCell>
							<TableCell numeric className="text-right">
								<MoneyCell>{formatCostUsd(row.instanceTotalCostUsd)}</MoneyCell>
							</TableCell>
							<TableCell numeric className="text-right">
								<MoneyCell>{formatCostUsd(row.ownProviderTotalCostUsd)}</MoneyCell>
							</TableCell>
							<TableCell numeric className="text-right">
								{row.unpricedEventCount.toLocaleString()}
							</TableCell>
							<TableCell numeric className="text-right">
								{row.events.toLocaleString()}
							</TableCell>
						</TableRow>
					))}
				</TableBody>
			)}
			{totals != null && (
				<TableFooter>
					<TableRow>
						<TableCell>Total</TableCell>
						<TableCell numeric className="text-right">
							<MoneyCell>{formatCostUsd(totals.priced)}</MoneyCell>
							<FxSpendLine usd={totals.priced} fx={fx} />
						</TableCell>
						<TableCell numeric className="text-right">
							<MoneyCell>{formatCostUsd(totals.ownProvider)}</MoneyCell>
							<FxSpendLine usd={totals.ownProvider} fx={fx} />
						</TableCell>
						<TableCell numeric className="text-right">
							{totals.unpriced.toLocaleString()}
						</TableCell>
						<TableCell numeric className="text-right">
							{totals.events.toLocaleString()}
						</TableCell>
					</TableRow>
				</TableFooter>
			)}
		</Table>
	);
}
