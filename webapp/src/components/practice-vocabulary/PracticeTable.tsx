import { ArrowRightIcon } from "lucide-react";
import { type ComponentProps, Fragment, type MouseEvent, type ReactNode } from "react";

import type { PracticeStanding, PracticeTrend, TrendSupport } from "@/api/types.gen";
import type { FeedbackTextSegment } from "@/components/common/feedback-text";
import { FeedbackText } from "@/components/common/FeedbackText";
import { SortButton } from "@/components/common/SortButton";
import { Button } from "@/components/ui/button";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import { cn } from "@/lib/utils";

import { nextPracticeGroupSort, type PracticeGroupSort } from "./practice-group-list-order";
import type { TrendScope } from "./practice-trend-presentation";
import { StandingBadge, TrendNote } from "./StandingBadge";

/** Skeleton rows while the first load is in flight, about a small workspace's worth. */
const LOADING_ROWS = 4;

export interface PracticeTableProps<TRow> {
	"aria-label": string;
	/**
	 * The table sorts by standing only; the caller orders `rows` under it and the Standing header
	 * shows it.
	 */
	sort: PracticeGroupSort;
	/** Called with the sort a press on the Standing header asks for. */
	onSortChange: (sort: PracticeGroupSort) => void;
	/** Heads between Standing and the subject column; a row renders the matching cells itself. */
	heads?: ReactNode;
	/** "Practice", "Practice group". */
	subjectHead: string;
	/** How many columns a row has, for the message rows; three without `heads`. */
	columns?: number;
	rows: readonly TRow[];
	rowKey: (row: TRow) => string;
	/**
	 * One `PracticeTableRow` per row: `StandingCell`, the cells under `heads`, `SubjectCell`,
	 * `RowLinkCell`.
	 */
	renderRow: (row: TRow) => ReactNode;
	/**
	 * What the table says when there are none, in the shape every practice surface says it in:
	 * the icon over a title and one sentence on when something will show up here.
	 */
	empty: { icon: ReactNode; title: string; description: string };
	isLoading?: boolean;
	/**
	 * One skeleton row, drawn a few times while loading; without one the body stays empty until
	 * then.
	 */
	loadingRow?: ReactNode;
}

/**
 * A table of practices or practice groups by standing, in the hairline frame the practice
 * surfaces draw around it: the sortable Standing column first, the subject and its sentence, and
 * the row's own "Open …" link last. The box scrolls sideways below its columns' width rather
 * than widening the page.
 */
export function PracticeTable<TRow>({
	"aria-label": label,
	sort,
	onSortChange,
	heads,
	subjectHead,
	columns = 3,
	rows,
	rowKey,
	renderRow,
	empty,
	isLoading = false,
	loadingRow,
}: PracticeTableProps<TRow>) {
	return (
		<Table
			aria-label={label}
			aria-busy={isLoading || undefined}
			containerClassName="rounded-xl border bg-background"
			className="min-w-152 [&_td]:py-3 [&_td:first-child]:pl-4 [&_td:last-child]:pr-4 [&_th:first-child]:pl-4 [&_th:last-child]:pr-4"
		>
			<TableHeader>
				<TableRow>
					<TableHead
						aria-sort={sort.direction === "asc" ? "ascending" : "descending"}
						className="w-60"
					>
						<SortButton
							sorted={sort.direction}
							onToggle={() => onSortChange(nextPracticeGroupSort(sort))}
							className="text-foreground"
						>
							Standing
						</SortButton>
					</TableHead>
					{heads}
					<TableHead>{subjectHead}</TableHead>
					<TableHead className="w-32">
						<span className="sr-only">Open</span>
					</TableHead>
				</TableRow>
			</TableHeader>
			<TableBody>
				{isLoading ? (
					loadingRow !== undefined &&
					Array.from({ length: LOADING_ROWS }, (_, index) => (
						<TableRow key={index} aria-hidden>
							{loadingRow}
						</TableRow>
					))
				) : rows.length === 0 ? (
					<TableRow className="hover:bg-transparent">
						{/* The cell is `whitespace-nowrap` by default, which the block's sentence must wrap
						    out of; the table's own row padding is what the block sits in. */}
						<TableCell colSpan={columns} className="whitespace-normal">
							<Empty className="p-4">
								<EmptyHeader>
									<EmptyMedia variant="icon">{empty.icon}</EmptyMedia>
									<EmptyTitle>{empty.title}</EmptyTitle>
									<EmptyDescription>{empty.description}</EmptyDescription>
								</EmptyHeader>
							</Empty>
						</TableCell>
					</TableRow>
				) : (
					rows.map((row) => <Fragment key={rowKey(row)}>{renderRow(row)}</Fragment>)
				)}
			</TableBody>
		</Table>
	);
}

/**
 * A click that landed on a nested control — a practice link — is that control's, and everything
 * else opens the row. The row's own link carries `data-row-link`, and a button that exists only
 * to put a tooltip within a keyboard's reach — the standing badge, the trend chip — carries
 * `data-tooltip-only`: a press on it answers nothing of its own, so it is the row's.
 */
function landedOnNestedControl(event: MouseEvent<HTMLElement>): boolean {
	if (!(event.target instanceof Element)) return false;
	const control = event.target.closest("button, a");
	return (
		control !== null &&
		!control.hasAttribute("data-row-link") &&
		!control.hasAttribute("data-tooltip-only")
	);
}

export interface PracticeTableRowProps extends Omit<ComponentProps<typeof TableRow>, "onClick"> {
	/** Opens what the row stands for; without it the row is not a control. */
	onOpen?: () => void;
	/**
	 * True while the row's detail level is open over the page; the row keeps a bar on its leading
	 * edge.
	 */
	open?: boolean;
}

/**
 * A row whose "Open …" link at its end is the keyboard path and whose whole surface is the pointer
 * path. Focus on that link tints the row, since the link itself is only as wide as its words; the
 * open row is marked by a bar drawn on its first cell, where a `<td>` positions reliably and a
 * `<tr>` does not.
 */
export function PracticeTableRow({
	onOpen,
	open = false,
	className,
	...props
}: PracticeTableRowProps) {
	return (
		<TableRow
			data-state={open ? "open" : undefined}
			className={cn(
				"group/row [&>td:first-child]:relative [&>td:first-child]:before:absolute [&>td:first-child]:before:inset-y-0 [&>td:first-child]:before:left-0 [&>td:first-child]:before:w-0.5 data-[state=open]:[&>td:first-child]:before:bg-mentor",
				"has-[[data-row-link]:focus-visible]:bg-muted/50",
				onOpen && "cursor-pointer",
				className,
			)}
			onClick={
				onOpen &&
				((event) => {
					if (!landedOnNestedControl(event)) onOpen();
				})
			}
			{...props}
		/>
	);
}

export interface StandingCellProps {
	standing: PracticeStanding["standing"];
	direction?: PracticeTrend["direction"];
	support?: TrendSupport;
	scope: TrendScope;
	className?: string;
}

/** The standing badge with the trend under it. */
export function StandingCell({
	standing,
	direction,
	support,
	scope,
	className,
}: StandingCellProps) {
	return (
		<TableCell className={cn("whitespace-normal", className)}>
			<div className="flex min-w-0 flex-col items-start gap-1.5">
				<StandingBadge standing={standing} scope={scope} />
				<TrendNote direction={direction} support={support} scope={scope} />
			</div>
		</TableCell>
	);
}

export interface SubjectCellProps {
	/** The pill or name of the practice or the group. */
	badge: ReactNode;
	/** What happened to it since the latest run; without one the row shows only the pill. */
	sentence?: FeedbackTextSegment[];
	/** Opens a practice named inside the sentence. */
	onOpenPractice?: (practiceSlug: string) => void;
	/** What else goes under the badge: the group table's list of events. */
	children?: ReactNode;
}

/** The subject's pill with its sentence under it, wrapping rather than widening the column. */
export function SubjectCell({ badge, sentence, onOpenPractice, children }: SubjectCellProps) {
	return (
		<TableCell className="whitespace-normal">
			<div className="flex min-w-0 flex-col items-start gap-2">
				{badge}
				{sentence && (
					<FeedbackText
						as="p"
						segments={sentence}
						onOpenPractice={onOpenPractice}
						className="max-w-md text-sm"
					/>
				)}
				{children}
			</div>
		</TableCell>
	);
}

export interface RowLinkCellProps {
	/** The words on the link: "Open group", "Open practice". */
	children: ReactNode;
	/**
	 * The link's accessible name, which says which one: "Open group Testing your changes". Without
	 * one the row is not a control and the cell stays, empty, so the columns keep their width.
	 */
	label?: string;
	className?: string;
}

/**
 * The row's own link at its end. A `variant="link"` button carrying `data-row-link` and no handler
 * of its own: the press bubbles to the row, which owns the opening, and the row lights up for
 * keyboard focus on this control alone. It follows `InlineLink`'s rule — plain at rest, mentor
 * blue with a solid underline on hover or focus — with the whole row as its hover, since the
 * whole row is the pointer path; the arrow is its own.
 */
export function RowLinkCell({ children, label, className }: RowLinkCellProps) {
	return (
		<TableCell className={cn("text-right", className)}>
			{label !== undefined && (
				<Button
					variant="link"
					size="inline"
					data-row-link
					aria-label={label}
					className="whitespace-nowrap font-medium decoration-1 underline-offset-3 group-hover/row:text-mentor group-hover/row:underline focus-visible:text-mentor focus-visible:underline"
				>
					{children}
					<ArrowRightIcon
						className="size-3.5 shrink-0 transition-transform motion-safe:group-hover/row:translate-x-0.5"
						aria-hidden
					/>
				</Button>
			)}
		</TableCell>
	);
}
