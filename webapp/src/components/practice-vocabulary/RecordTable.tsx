import { Fragment, type ReactNode } from "react";

import { cn } from "cn";
import type { StatusDef } from "@/components/common/status-def";
import { useNow } from "@/components/common/use-now";
import { groupByDay } from "@/components/practice-trace/review-groups";
import { Skeleton } from "@/components/ui/skeleton";
import {
	Table,
	TableBody,
	TableCell,
	TableFooter,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import { formatDayTime, formatTime } from "@/lib/dates";

import { OPEN_ROW_BAR, ROW_ACTIONS_RULE } from "./PracticeTable";
import { StatusGlyph, StatusIcon } from "./StatusTooltip";

/** One head of a record table; each row draws a cell under every one. */
export interface RecordTableColumn {
	/** The head's words; a column with only a control in it names itself for a screen reader alone. */
	label: string;
	srOnly?: boolean;
	/** A count: tabular figures, aligned to the right, head and cells alike. */
	numeric?: boolean;
	/** The id of a note that explains the head, such as the line under the table. */
	describedBy?: string;
	/** A control after the head's words, such as an (i) that explains what the column holds. */
	info?: ReactNode;
	className?: string;
}

/**
 * The row's own cell at its end — its Track, Trace and "Open …" link — sits at the top of the row,
 * as every other cell does, so the controls stand at one place in every table whatever a row's
 * height.
 */
const ROW_END_TOP = "[&_tbody_td:last-child]:align-top";

/**
 * Below the frame's `@2xl` a row is no longer a line of columns: the time stands on the left and
 * every other cell stacks beside it, its controls last, so nothing is cut off or out of reach on a
 * phone. Each row is its own grid, so the time's track has a floor that fits a status icon and any
 * time of day: the second column then starts at the same x in every row. The head is kept for a
 * screen reader; a cell whose value needs its column's name to be read
 * carries it in `data-label`, which is drawn before the value.
 */
const STACKED = [
	"@max-2xl:block @max-2xl:min-w-0",
	"@max-2xl:[&_thead]:sr-only @max-2xl:[&_tbody]:block @max-2xl:[&_tfoot]:block",
	"@max-2xl:[&_tr]:grid @max-2xl:[&_tr]:grid-cols-[minmax(6rem,auto)_minmax(0,1fr)] @max-2xl:[&_tr]:gap-x-3 @max-2xl:[&_tr]:px-2 @max-2xl:[&_tr]:py-2.5",
	"@max-2xl:[&_tr:has(th)]:p-0 @max-2xl:[&_th[scope=rowgroup]]:col-span-2",
	"@max-2xl:[&_tr[data-separator]]:p-0 @max-2xl:[&_tr[data-separator]_td]:pb-0",
	"@max-2xl:[&_td]:col-start-2 @max-2xl:[&_td]:block @max-2xl:[&_td]:p-0 @max-2xl:[&_td]:pb-1.5 @max-2xl:[&_td]:text-left @max-2xl:[&_td]:whitespace-normal",
	"@max-2xl:[&_td:first-child]:col-start-1 @max-2xl:[&_td:first-child]:row-span-12",
	"@max-2xl:[&_td[colspan]]:col-span-2! @max-2xl:[&_td[colspan]]:col-start-1!",
	"@max-2xl:[&_td[data-label]]:before:text-muted-foreground @max-2xl:[&_td[data-label]]:before:content-[attr(data-label)_':_']",
].join(" ");

interface RecordTableFrameProps {
	"aria-label": string;
	columns: readonly RecordTableColumn[];
	busy?: boolean;
	/** Classes on the `<table>`, such as the dimming of a stale answer. */
	tableClassName?: string;
	/** A note under the rows, inside the frame. */
	footer?: ReactNode;
	children: ReactNode;
}

/** The frame and its head; the caller draws the bodies. */
function RecordTableFrame({
	"aria-label": label,
	columns,
	busy = false,
	tableClassName,
	footer,
	children,
}: RecordTableFrameProps) {
	return (
		// `shrink-0`: an `overflow-hidden` flex item has no automatic minimum size, so a level's
		// column would squash the frame and clip its last rows.
		<div className="@container shrink-0 overflow-hidden rounded-xl border bg-background">
			<Table
				aria-label={label}
				aria-busy={busy || undefined}
				className={cn("min-w-152", ROW_END_TOP, ROW_ACTIONS_RULE, STACKED, tableClassName)}
			>
				<TableHeader>
					<TableRow>
						{columns.map((column) => (
							<TableHead
								key={column.label}
								numeric={column.numeric}
								aria-describedby={column.describedBy}
								className={cn(column.numeric === true && "text-right", column.className)}
							>
								{column.srOnly === true && <span className="sr-only">{column.label}</span>}
								{column.srOnly !== true && column.info === undefined && column.label}
								{column.srOnly !== true && column.info !== undefined && (
									<span className="inline-flex items-center gap-1">
										{column.label}
										{column.info}
									</span>
								)}
							</TableHead>
						))}
					</TableRow>
				</TableHeader>
				{children}
			</Table>
			{footer}
		</div>
	);
}

/** What the table says when it has no rows, in a cell across every column. */
function EmptyBody({ span, children }: { span: number; children: ReactNode }) {
	return (
		<TableBody>
			<TableRow variant="static">
				{/* The cell is `whitespace-nowrap` by default, which the block's sentence must wrap out of. */}
				<TableCell colSpan={span} className="p-4 whitespace-normal">
					{children}
				</TableCell>
			</TableRow>
		</TableBody>
	);
}

interface RecordRowsProps<TRow> {
	"aria-label": string;
	columns: readonly RecordTableColumn[];
	rows: readonly TRow[];
	rowKey: (row: TRow) => string;
	/** One `PracticeTableRow` with a cell per column; the row draws its own link in the last. */
	renderRow: (row: TRow) => ReactNode;
	/** What the table says when there are no rows. */
	empty: ReactNode;
	/** Classes on the `<table>`, such as a list that centers its cells in the row. */
	tableClassName?: string;
}

export interface RecordDayTableProps<TRow> extends RecordRowsProps<TRow> {
	/** The moment a row is listed at, which picks its day. Rows come newest first. */
	dateOf: (row: TRow) => Date;
	/** See `RecordGroupedTableProps.loadingMore`. */
	loadingMore?: number;
}

export function RecordDayTable<TRow>({ dateOf, rows, ...props }: RecordDayTableProps<TRow>) {
	const days = groupByDay(rows, dateOf, new Date(useNow()));
	return <RecordGroupedTable {...props} groups={days} />;
}

export interface RecordGroupedTableProps<TRow> extends Omit<RecordRowsProps<TRow>, "rows"> {
	/** The bodies in order, each under its heading; a table with none says `empty`. */
	groups: readonly { label: string; rows: readonly TRow[] }[];
	/**
	 * Placeholder rows after the last body while the next page loads. The table stays busy and
	 * keeps its columns.
	 */
	loadingMore?: number;
}

/**
 * Records one body per group under its heading: a day, or another order's rank, so every order of
 * a stream keeps the same shape.
 */
export function RecordGroupedTable<TRow>({
	"aria-label": label,
	columns,
	groups,
	rowKey,
	renderRow,
	empty,
	loadingMore = 0,
	tableClassName,
}: RecordGroupedTableProps<TRow>) {
	return (
		<RecordTableFrame
			aria-label={label}
			columns={columns}
			busy={loadingMore > 0}
			tableClassName={tableClassName}
		>
			{groups.length === 0 && <EmptyBody span={columns.length}>{empty}</EmptyBody>}
			{groups.map((group) => (
				<TableBody key={group.label}>
					<TableRow variant="static">
						{/* `border-t`: a body's last row drops its border, so the line above a group is
						    the heading's; the collapsed table draws one line where it meets the head. */}
						<th
							scope="rowgroup"
							colSpan={columns.length}
							className="border-t bg-sidebar px-2 py-1.5 text-left text-xs font-medium text-muted-foreground"
						>
							{group.label}
						</th>
					</TableRow>
					{group.rows.map((row) => (
						<Fragment key={rowKey(row)}>{renderRow(row)}</Fragment>
					))}
				</TableBody>
			))}
			{loadingMore > 0 && (
				<TableBody>
					{/* A body's last row drops its border, so this line keeps the last record apart from the
					    placeholders, as a group heading's line does. */}
					<tr aria-hidden data-separator>
						<td colSpan={columns.length} className="border-t p-0" />
					</tr>
					<SkeletonRows columns={columns} rows={loadingMore} tall />
				</TableBody>
			)}
		</RecordTableFrame>
	);
}

export interface RecordTableProps<TRow> extends RecordRowsProps<TRow> {
	busy?: boolean;
	tableClassName?: string;
	/** Rows after the records that stand for no record, such as a closing sentence; drawn when empty too. */
	after?: ReactNode;
	/** A row of totals in the table's foot, under every record. */
	totals?: ReactNode;
	footer?: ReactNode;
}

/** Records in one body, in the order given: a table that is no stream, such as an overview. */
export function RecordTable<TRow>({
	"aria-label": label,
	columns,
	rows,
	rowKey,
	renderRow,
	empty,
	busy,
	tableClassName,
	after,
	totals,
	footer,
}: RecordTableProps<TRow>) {
	return (
		<RecordTableFrame
			aria-label={label}
			columns={columns}
			busy={busy}
			tableClassName={tableClassName}
			footer={footer}
		>
			<TableBody>
				{rows.length === 0 ? (
					<TableRow variant="static">
						<TableCell colSpan={columns.length} className="p-4 whitespace-normal">
							{empty}
						</TableCell>
					</TableRow>
				) : (
					rows.map((row) => <Fragment key={rowKey(row)}>{renderRow(row)}</Fragment>)
				)}
				{after}
			</TableBody>
			{totals !== undefined && <TableFooter>{totals}</TableFooter>}
		</RecordTableFrame>
	);
}

const SKELETON_WIDTHS = ["w-16", "w-48", "w-32", "w-24"];
/** The same widths as caps, for a placeholder among real rows. */
const TALL_SKELETON_WIDTHS = ["max-w-16", "max-w-48", "max-w-32", "max-w-24"];

export interface RecordTableSkeletonProps {
	"aria-label": string;
	columns: readonly RecordTableColumn[];
	/**
	 * The page size the table is about to show. Deliberately undefaulted: a default can silently
	 * disagree with the caller's page size, and the rows then arrive by moving the page.
	 */
	rows: number;
	tableClassName?: string;
	footer?: ReactNode;
}

/** The table's frame and head with placeholder rows, while the first page loads. */
export function RecordTableSkeleton({
	"aria-label": label,
	columns,
	rows,
	tableClassName,
	footer,
}: RecordTableSkeletonProps) {
	return (
		<RecordTableFrame
			aria-label={label}
			columns={columns}
			busy
			tableClassName={tableClassName}
			footer={footer}
		>
			<TableBody>
				<SkeletonRows columns={columns} rows={rows} />
			</TableBody>
		</RecordTableFrame>
	);
}

/**
 * Placeholder rows in the table's own columns: the first page's, and the next page's while it
 * loads. A `tall` row stands among rows already drawn: two lines, as a record's title and its facts,
 * and widths that only cap a bar, so a placeholder never widens a column that real rows have set.
 */
function SkeletonRows({
	columns,
	rows,
	tall = false,
}: {
	columns: readonly RecordTableColumn[];
	rows: number;
	tall?: boolean;
}) {
	return Array.from({ length: rows }, (_, index) => (
		<TableRow key={index} variant="static" aria-hidden data-loading-row>
			{columns.map((column, columnIndex) => {
				if (column.srOnly === true) {
					return <TableCell key={column.label} className="align-top" />;
				}
				const slot = Math.min(columnIndex, SKELETON_WIDTHS.length - 1);
				const width = column.numeric === true ? "ml-auto w-10" : SKELETON_WIDTHS[slot];
				const cap = column.numeric === true ? "ml-auto max-w-10" : TALL_SKELETON_WIDTHS[slot];
				return (
					<TableCell key={column.label} className="align-top">
						{tall ? (
							<div className="flex flex-col gap-2 py-1">
								<Skeleton className={cn("h-4 w-full", cap)} />
								<Skeleton className="h-3 w-full max-w-24" />
							</div>
						) : (
							<Skeleton className={cn("h-4", width)} />
						)}
					</TableCell>
				);
			})}
		</TableRow>
	));
}

export interface RecordTimeCellProps {
	/** The record's state as an icon, whose tooltip says the words; absent when it is unknown. */
	status?: StatusDef;
	at: Date;
	/**
	 * `false` draws the state as a plain icon with no focus stop, for a list whose row keeps one
	 * control. The words still reach a screen reader and a pointer's native title.
	 */
	statusTooltip?: boolean;
	/**
	 * Names the day as well, for a table with no day headings: one in an order other than time, where
	 * the same day would otherwise head the rows again each time it came back.
	 */
	withDay?: boolean;
}

/** The time of day beside the record's state; the day is the heading above, or `withDay` says it. */
export function RecordTimeCell({
	status,
	at,
	statusTooltip = true,
	withDay = false,
}: RecordTimeCellProps) {
	const today = new Date(useNow());
	return (
		<TableCell className={cn(OPEN_ROW_BAR, "align-top whitespace-nowrap")}>
			<span className="flex min-w-0 items-center gap-2">
				{status !== undefined &&
					(statusTooltip ? <StatusIcon def={status} /> : <StatusGlyph def={status} />)}
				<time dateTime={at.toISOString()} className="font-semibold">
					{withDay ? formatDayTime(at, today) : formatTime(at)}
				</time>
			</span>
		</TableCell>
	);
}
