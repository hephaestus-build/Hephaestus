import { ArrowRightIcon } from "lucide-react";
import { type ComponentProps, Fragment, type MouseEvent, type ReactNode } from "react";

import { cn } from "cn";
import type { PracticeStanding, PracticeTrend, TrendSupport } from "@/api/types.gen";
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

import type { FeedbackTextSegment } from "./feedback-text";
import { FeedbackText } from "./FeedbackText";
import type { SortDirection } from "./practice-group-list-order";
import type { StandingScope } from "./practice-group-standing-defs";
import { StandingBadge, TrendNote } from "./StandingBadge";

/** Skeleton rows while the first load is in flight, about a small workspace's worth. */
const LOADING_ROWS = 4;

export interface PracticeTableHead {
	label: ReactNode;
	span?: number;
}

/** Standing, the subject and the row's link: the columns every practice table has. */
const FIXED_COLUMNS = 3;

const NO_HEADS: readonly PracticeTableHead[] = [];

export interface PracticeTableProps<TRow> {
	"aria-label": string;
	/**
	 * The table sorts by standing only; the caller orders `rows` under it and the Standing header
	 * shows it.
	 */
	sort: SortDirection;
	/** Called with the sort a press on the Standing header asks for. */
	onSortChange: (sort: SortDirection) => void;
	/**
	 * Heads between Standing and the subject column, each over `span` columns (one by default); a
	 * row renders the matching cells itself.
	 */
	heads?: readonly PracticeTableHead[];
	/** "Practice", "Practice group". */
	subjectHead: string;
	rows: readonly TRow[];
	rowKey: (row: TRow) => string;
	/**
	 * One `PracticeTableRow` per row: `StandingCell`, the cells under `heads`, `SubjectCell`; the
	 * row draws its own link at its end.
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
	heads = NO_HEADS,
	subjectHead,
	rows,
	rowKey,
	renderRow,
	empty,
	isLoading = false,
	loadingRow,
}: PracticeTableProps<TRow>) {
	const columns = heads.reduce((sum, head) => sum + (head.span ?? 1), FIXED_COLUMNS);
	let body: ReactNode;
	if (isLoading) {
		body =
			loadingRow !== undefined &&
			Array.from({ length: LOADING_ROWS }, (_, index) => (
				<TableRow key={index} aria-hidden>
					{loadingRow}
				</TableRow>
			));
	} else if (rows.length === 0) {
		body = (
			<TableRow variant="static">
				{/* The cell is `whitespace-nowrap` by default, which the block's sentence must wrap
				    out of; the cell's own padding is what the block sits in. */}
				<TableCell colSpan={columns} className="p-4 whitespace-normal">
					<Empty>
						<EmptyHeader>
							<EmptyMedia variant="icon">{empty.icon}</EmptyMedia>
							<EmptyTitle>{empty.title}</EmptyTitle>
							<EmptyDescription>{empty.description}</EmptyDescription>
						</EmptyHeader>
					</Empty>
				</TableCell>
			</TableRow>
		);
	} else {
		body = rows.map((row) => <Fragment key={rowKey(row)}>{renderRow(row)}</Fragment>);
	}
	return (
		<div className="overflow-hidden rounded-xl border bg-background">
			<Table aria-label={label} aria-busy={isLoading || undefined} className="min-w-152">
				<TableHeader>
					<TableRow>
						<TableHead aria-sort={sort === "asc" ? "ascending" : "descending"} className="w-60">
							<SortButton
								sorted={sort}
								onToggle={() => onSortChange(sort === "asc" ? "desc" : "asc")}
								className="text-foreground"
							>
								Standing
							</SortButton>
						</TableHead>
						{heads.map((head, index) => (
							<TableHead key={index} colSpan={head.span}>
								{head.label}
							</TableHead>
						))}
						<TableHead>{subjectHead}</TableHead>
						<TableHead className="w-32">
							<span className="sr-only">Open</span>
						</TableHead>
					</TableRow>
				</TableHeader>
				<TableBody>{body}</TableBody>
			</Table>
		</div>
	);
}

/**
 * A click that landed on a nested control — a practice link — is that control's, and everything
 * else opens the row. The row's own link carries `data-row-link`, and a button that exists only
 * to put a tooltip within a keyboard's reach — the standing badge, the trend chip — carries
 * `data-tooltip-only`: a pointer's press on it answers nothing of its own, so it is the row's. A
 * keyboard's Enter or Space on it arrives as a click with `detail` 0, and that reader came for the
 * sentence, not the row; the row's keyboard path is its own link.
 */
function landedOnNestedControl(event: MouseEvent<HTMLElement>): boolean {
	if (!(event.target instanceof Element)) {
		return false;
	}
	const control = event.target.closest<HTMLElement>("button, a");
	if (control === null || Object.hasOwn(control.dataset, "rowLink")) {
		return false;
	}
	return !Object.hasOwn(control.dataset, "tooltipOnly") || event.detail === 0;
}

/**
 * The row's own link at its end and what it opens. One record, so a row never draws a link that
 * opens nothing, nor opens on a press with no link for a keyboard to reach.
 */
export interface PracticeTableRowLink {
	/** The words on the link: "Open group", "Open practice". */
	text: string;
	/**
	 * What the row stands for — "Testing your changes" — which the link's accessible name adds after
	 * the words: every row's link reads the same, and the name is what tells them apart.
	 */
	name: string;
	onOpen: () => void;
}

export interface PracticeTableRowProps extends Omit<ComponentProps<typeof TableRow>, "onClick"> {
	/**
	 * Opens what the row stands for, from the whole row and the link at its end; without it the row
	 * is not a control and the link's cell stays, empty, so the columns keep their width.
	 */
	link?: PracticeTableRowLink;
	/**
	 * True while the row's detail level is open over the page; the row keeps a bar on its leading
	 * edge.
	 */
	open?: boolean;
}

/**
 * The bar on the open row's leading edge, worn by `StandingCell` since every row puts it first: a
 * `<td>` positions it reliably and a `<tr>` does not. It takes the accent from the row's
 * `data-state`.
 */
const OPEN_ROW_BAR =
	"relative before:absolute before:inset-y-0 before:left-0 before:w-0.5 group-data-[state=open]/row:before:bg-mentor";

/**
 * A row whose "Open …" link at its end is the keyboard path and whose whole surface is the pointer
 * path; the link carries its own focus styles, since it is only as wide as its words.
 */
export function PracticeTableRow({
	link,
	open = false,
	className,
	children,
	...props
}: PracticeTableRowProps) {
	return (
		<TableRow
			data-state={open ? "open" : undefined}
			className={cn("group/row", link && "cursor-pointer", className)}
			onClick={
				link &&
				((event) => {
					if (!landedOnNestedControl(event)) {
						link.onOpen();
					}
				})
			}
			{...props}
		>
			{children}
			<RowLinkCell link={link} />
		</TableRow>
	);
}

export interface StandingCellProps {
	standing: PracticeStanding["standing"];
	direction?: PracticeTrend["direction"];
	support?: TrendSupport;
	scope: StandingScope;
}

/** The standing badge with the trend under it. */
export function StandingCell({ standing, direction, support, scope }: StandingCellProps) {
	return (
		<TableCell className={cn(OPEN_ROW_BAR, "whitespace-normal")}>
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
	/** What else goes under the badge: the group table's list of events. */
	children?: ReactNode;
}

/** The subject's pill with its sentence under it, wrapping rather than widening the column. */
export function SubjectCell({ badge, sentence, children }: SubjectCellProps) {
	return (
		<TableCell className="whitespace-normal">
			<div className="flex min-w-0 flex-col items-start gap-2">
				{badge}
				{sentence && <FeedbackText as="p" segments={sentence} className="max-w-md text-sm" />}
				{children}
			</div>
		</TableCell>
	);
}

/**
 * The row's own link at its end. A `variant="link"` button carrying `data-row-link` and no handler
 * of its own: the press bubbles to the row, which owns the opening. It takes `InlineLink`'s hover
 * from the whole row, since the whole row is the pointer path. The accessible name is built from
 * the visible words, so it always starts with what is on screen and a reader who speaks them
 * reaches the link (WCAG 2.2 SC 2.5.3). An `aria-label` rather than hidden text after the words:
 * how name-from-content joins an out-of-flow child to the text before it differs between engines,
 * and a label is one string everywhere.
 */
function RowLinkCell({ link }: { link: PracticeTableRowLink | undefined }) {
	return (
		<TableCell className="text-right">
			{link && (
				<Button
					variant="link"
					size="inline"
					data-row-link
					aria-label={`${link.text} ${link.name}`}
					className="font-medium whitespace-nowrap decoration-1 underline-offset-3 group-hover/row:text-mentor group-hover/row:underline focus-visible:text-mentor focus-visible:underline"
				>
					{link.text}
					<ArrowRightIcon
						className="size-3.5 shrink-0 transition-transform motion-safe:group-hover/row:translate-x-0.5"
						aria-hidden
					/>
				</Button>
			)}
		</TableCell>
	);
}
