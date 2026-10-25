import { ArrowRightIcon } from "lucide-react";
import {
	type ComponentProps,
	Fragment,
	type MouseEvent,
	type ReactElement,
	type ReactNode,
} from "react";

import { cn } from "cn";
import type { PracticeStanding, TrendSupport } from "@/api/types.gen";
import { ROW_CONTROL_FOCUS } from "@/components/common/focus";
import { InlineLink } from "@/components/common/InlineLink";
import { SortButton } from "@/components/common/SortButton";
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
import type { GroupMark } from "./group-visuals";
import type { SortDirection } from "./practice-group-list-order";
import type { StandingScope } from "./practice-group-standing-defs";
import type { TrendDirection } from "./practice-trend-defs";
import type { StandingBasis } from "./standing-basis-defs";
import { StandingBadge, TrendNote } from "./StandingBadge";
import { StandingBasisNote } from "./StandingBasisNote";

/** Skeleton rows while the first load is in flight, about a small workspace's worth. */
const LOADING_ROWS = 4;

export interface PracticeTableHead {
	label: ReactNode;
	span?: number;
}

/** Standing, the subject and the row's link: the columns every practice table has. */
const FIXED_COLUMNS = 3;

const NO_HEADS: readonly PracticeTableHead[] = [];

interface PracticeTableBody<TRow> {
	"aria-label": string;
	"aria-describedby"?: string;
	rows: readonly TRow[];
	rowKey: (row: TRow) => string;
	renderRow: (row: TRow) => ReactNode;
	/**
	 * What the table says when there are none, in the shape every practice surface says it in:
	 * the icon over a title and one sentence on when something will show up here. `null` when the
	 * table's own sections hold the rows instead, so no empty state claims there are none.
	 */
	empty: { icon: ReactNode; title: string; description: string } | null;
	isLoading?: boolean;
	/**
	 * One skeleton row, drawn a few times while loading; without one the body stays empty until
	 * then.
	 */
	loadingRow?: ReactNode;
}

export interface PracticeTableFrameProps<TRow> extends PracticeTableBody<TRow> {
	head: ReactNode;
	/** The empty state spans this many columns. */
	columns: number;
	/** Classes on the `<table>`, such as the narrow layout `PracticeTable` gives its rows. */
	tableClassName?: string;
	/**
	 * Further `<tbody>` sections under the rows, in the same table, so their cells share its columns:
	 * the folds of the Practice profile.
	 */
	sections?: ReactNode;
}

/**
 * The hairline frame every practice table is drawn in. It scrolls sideways below its columns'
 * width instead of widening the page.
 */
export function PracticeTableFrame<TRow>({
	"aria-label": label,
	"aria-describedby": describedBy,
	head,
	columns,
	rows,
	rowKey,
	renderRow,
	empty,
	isLoading = false,
	loadingRow,
	tableClassName,
	sections,
}: PracticeTableFrameProps<TRow>) {
	let body: ReactNode;
	if (isLoading) {
		body =
			loadingRow !== undefined &&
			Array.from({ length: LOADING_ROWS }, (_, index) => (
				<TableRow key={index} aria-hidden>
					{loadingRow}
				</TableRow>
			));
	} else if (rows.length === 0 && empty !== null) {
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
		// `shrink-0`: an `overflow-hidden` flex item has no automatic minimum size, so a level's column
		// would squash the frame and clip its last rows.
		<div className="@container shrink-0 overflow-hidden rounded-xl border bg-background">
			<Table
				aria-label={label}
				aria-describedby={describedBy}
				aria-busy={isLoading || undefined}
				className={cn("min-w-152", tableClassName)}
			>
				<TableHeader>
					<TableRow>{head}</TableRow>
				</TableHeader>
				<TableBody>{body}</TableBody>
				{sections}
			</Table>
		</div>
	);
}

export interface PracticeTableProps<TRow> extends PracticeTableBody<TRow> {
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
	/** An (i) beside the subject's heading that says what the subject is, for a page that says it nowhere else. */
	subjectInfo?: ReactNode;
	/**
	 * One `PracticeTableRow` per row: `StandingCell`, the cells under `heads`, `SubjectCell`; the
	 * row draws its own link at its end.
	 */
	renderRow: (row: TRow) => ReactNode;
	/**
	 * Sections under the rows in the same table, given the table's column count so a row of theirs
	 * can span it: a fold's toggle row and its rows line up with the columns above by construction.
	 */
	sections?: (columns: number) => ReactNode;
}

/**
 * Below the width the columns need (`min-w-152`), as in a sheet on a phone, a row is no longer a
 * line of columns that scrolls sideways out of sight: it stacks, the subject's name first and whole,
 * then the standing and the counts side by side, then the row's link. The Standing sort stays
 * visible; the other headings stay available to a screen reader.
 */
export const STACKED = [
	"@max-[38rem]:block @max-[38rem]:min-w-0",
	"@max-[38rem]:[&_thead]:block @max-[38rem]:[&_tbody]:block",
	"@max-[38rem]:[&_th:not([data-standing-sort])]:sr-only @max-[38rem]:[&_th[data-standing-sort]]:block @max-[38rem]:[&_th[data-standing-sort]]:w-auto @max-[38rem]:[&_th[data-standing-sort]]:p-0",
	"@max-[38rem]:[&_tr]:flex @max-[38rem]:[&_tr]:flex-wrap @max-[38rem]:[&_tr]:items-start @max-[38rem]:[&_tr]:gap-x-4 @max-[38rem]:[&_tr]:gap-y-2 @max-[38rem]:[&_tr]:px-3 @max-[38rem]:[&_tr]:py-3",
	"@max-[38rem]:[&_td]:block @max-[38rem]:[&_td]:min-w-0 @max-[38rem]:[&_td]:p-0 @max-[38rem]:[&_td]:whitespace-normal",
	"@max-[38rem]:[&_td[data-subject]]:order-first @max-[38rem]:[&_td[data-subject]]:basis-full",
	"@max-[38rem]:[&_td:last-child]:basis-full @max-[38rem]:[&_td:last-child]:text-left",
	"@max-[38rem]:[&_td[colspan]]:basis-full",
].join(" ");

/** Practices or practice groups by standing: Standing, the subject, then the row's link. */
export function PracticeTable<TRow>({
	sort,
	onSortChange,
	heads = NO_HEADS,
	subjectHead,
	subjectInfo,
	sections,
	...body
}: PracticeTableProps<TRow>) {
	const columns = heads.reduce((sum, head) => sum + (head.span ?? 1), FIXED_COLUMNS);
	return (
		<PracticeTableFrame
			{...body}
			tableClassName={STACKED}
			columns={columns}
			sections={sections?.(columns)}
			head={
				<>
					<TableHead
						data-standing-sort
						aria-sort={sort === "asc" ? "ascending" : "descending"}
						className="w-60"
					>
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
					<TableHead>
						{subjectInfo === undefined ? (
							subjectHead
						) : (
							// The words in the foreground, as the Standing head's are; the (i) keeps its own tone.
							<span className="inline-flex items-center gap-1">
								<span className="text-foreground">{subjectHead}</span>
								{subjectInfo}
							</span>
						)}
					</TableHead>
					<TableHead className="w-32">
						<span className="sr-only">Open</span>
					</TableHead>
				</>
			}
		/>
	);
}

/**
 * A click that landed on a nested control — a practice link — is that control's, and everything
 * else opens the row. The row's own link carries `data-row-link`, and a button that exists only
 * to put a sentence within reach — the standing badge, the trend chip — carries
 * `data-tooltip-only`: a mouse press on it answers nothing of its own, so it is the row's, and the
 * hover already showed the sentence. A tap opens the sentence and stops at the button
 * (`TipPopover`). A keyboard's Enter or Space arrives as a click with `detail` 0, and that reader
 * came for the sentence, not the row; the row's keyboard path is its own link.
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

/** A press on the row's own link, which a real link answers itself. */
function landedOnRowLink(event: MouseEvent<HTMLElement>): boolean {
	return event.target instanceof Element && event.target.closest("[data-row-link]") !== null;
}

/**
 * The row's own link at its end and what it opens. One record, so a row never draws a link that
 * opens nothing, nor opens on a press with no link for a keyboard to reach.
 */
export type PracticeTableRowLink = {
	/** The words on the link: "Open group", "Open practice". */
	text: string;
	/**
	 * What the row stands for — "Testing your changes" — which the link's accessible name adds after
	 * the words: every row's link reads the same, and the name is what tells them apart.
	 */
	name: string;
} & (
	| { onOpen: () => void; render?: undefined }
	| {
			/**
			 * A real link to open instead, such as a `DetailStackLink`, so the row also opens in a new
			 * tab. A press elsewhere on the row is passed to it, so both paths open the same address.
			 */
			render: ReactElement;
			onOpen?: undefined;
	  }
);

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
	/**
	 * True for the row the reader came from on another page, with no level open: it keeps the same
	 * bar, and `aria-current` says it to a screen reader.
	 */
	marked?: boolean;
	/**
	 * The row's own controls, such as Track, Trace and Request review, in a column of their own before
	 * the link's: the table's head names it. A press anywhere in that cell is its own and never opens
	 * the row, so a control that misses its button by a pixel does not open the sheet instead.
	 */
	action?: ReactNode;
}

/**
 * The rule before a row's actions column, for the table that holds `PracticeTableRow`s with an
 * `action`: drawn by the table, since a cell owns its own shape.
 */
export const ROW_ACTIONS_RULE =
	"[&_td[data-row-actions]]:border-l @max-2xl:[&_td[data-row-actions]]:border-l-0";

/**
 * The bar on the open row's leading edge, worn by the first cell of a `PracticeTableRow`, which
 * is `StandingCell` here: a `<td>` positions it reliably and a `<tr>` does not. It takes the
 * accent from the row's `data-state`, from a marked row's `aria-current`, or from the row's own link
 * when that link is a real one to the level in front: the router marks it `aria-current`, so what a
 * screen reader hears and what the eye sees are one fact.
 */
export const OPEN_ROW_BAR =
	"relative before:absolute before:inset-y-0 before:left-0 before:w-0.5 group-data-[state=open]/row:before:bg-mentor group-aria-[current=true]/row:before:bg-mentor group-has-[[data-row-link][aria-current=page]]/row:before:bg-mentor";

/**
 * A row whose "Open …" link at its end is the keyboard path and whose whole surface is the pointer
 * path; the link carries its own focus styles, since it is only as wide as its words.
 */
export function PracticeTableRow({
	link,
	open = false,
	marked = false,
	action,
	className,
	children,
	...props
}: PracticeTableRowProps) {
	return (
		<TableRow
			data-state={open ? "open" : undefined}
			aria-current={marked || undefined}
			// A badge's panel leaves a mouse press inside this row to the row (`TipPopover`).
			data-row-press={link ? "" : undefined}
			className={cn("group/row", link && "cursor-pointer", className)}
			onClick={
				link &&
				((event) => {
					// A press inside a dialog a row's control opened arrives here too, because React passes a
					// portal's events to its React parents; it is not a press on the row.
					if (!(event.target instanceof Node) || !event.currentTarget.contains(event.target)) {
						return;
					}
					// The actions column is the row's controls' own, gaps between them included.
					if (event.target instanceof Element && event.target.closest("[data-row-actions]")) {
						return;
					}
					if (landedOnNestedControl(event)) {
						return;
					}
					if (link.render === undefined) {
						link.onOpen();
					} else if (!landedOnRowLink(event)) {
						event.currentTarget.querySelector<HTMLElement>("[data-row-link]")?.click();
					}
				})
			}
			{...props}
		>
			{children}
			{action !== undefined && (
				// The table draws a rule on its leading edge (`ROW_ACTIONS_RULE`), which sets the controls
				// apart from the facts the row opens on.
				<TableCell data-row-actions className="cursor-default align-top">
					<span className="flex items-center gap-2">{action}</span>
				</TableCell>
			)}
			<RowLinkCell link={link} />
		</TableRow>
	);
}

export interface StandingCellProps {
	standing: PracticeStanding["standing"];
	direction?: TrendDirection;
	support?: TrendSupport;
	scope: StandingScope;
	/** What the standing was read from; earlier reviews are said under the badge. */
	basis?: StandingBasis | null;
}

/** The standing badge with the trend under it, and under both whether it rests on earlier reviews. */
export function StandingCell({ standing, direction, support, scope, basis }: StandingCellProps) {
	return (
		<TableCell className={cn(OPEN_ROW_BAR, "whitespace-normal")}>
			<div className="flex min-w-0 flex-col items-start gap-1.5">
				{/* A little more room between the standing and its trend, so the two read as two facts;
				    the note on earlier reviews keeps its own spacing. */}
				<span className="flex flex-col items-start gap-2.5">
					<StandingBadge standing={standing} scope={scope} support={support} />
					<TrendNote direction={direction} support={support} scope={scope} />
				</span>
				<StandingBasisNote basis={basis} />
			</div>
		</TableCell>
	);
}

export interface SubjectCellProps {
	/** The pill or name of the practice or the group. */
	badge: ReactNode;
	/** What happened to it since the latest run, one bullet per sentence; none shows only the pill. */
	sentences?: FeedbackTextSegment[][];
	/** Opens a practice a sentence names. */
	onOpenPractice?: (practiceSlug: string) => void;
	/** The group the sentences' practices belong to, for their labels (`FeedbackTextProps.practiceGroup`). */
	practiceGroup?: GroupMark;
	/** What else goes under the badge: the group table's list of events. */
	children?: ReactNode;
	/** `OPEN_ROW_BAR` where the subject is the row's first cell. */
	className?: string;
}

/** The subject's pill with its sentence under it, wrapping rather than widening the column. */
export function SubjectCell({
	badge,
	sentences,
	onOpenPractice,
	practiceGroup,
	children,
	className,
}: SubjectCellProps) {
	return (
		<TableCell data-subject className={cn("whitespace-normal", className)}>
			<div className="flex min-w-0 flex-col items-start gap-2">
				{badge}
				{sentences !== undefined && sentences.length > 0 && (
					// `w-full`: in the cell's `items-start` column the list would shrink to its words, and
					// a row pill in a sentence would then wrap at its own width instead of the column's.
					<ul className="w-full max-w-md list-disc space-y-1 pl-4 text-sm marker:text-muted-foreground">
						{sentences.map((sentence, index) => (
							<li key={index}>
								<FeedbackText
									segments={sentence}
									onOpenPractice={onOpenPractice}
									practiceGroup={practiceGroup}
								/>
							</li>
						))}
					</ul>
				)}
				{children}
			</div>
		</TableCell>
	);
}

/**
 * The look of a row's own link, for a table that draws its link itself: plain at rest, the accent
 * and an underline while the row (`group/row`) is hovered, with its arrow after the words.
 */
export const ROW_LINK_CLASS = cn(
	"inline-flex items-center gap-1 font-medium whitespace-nowrap group-hover/row:text-mentor group-hover/row:underline",
	ROW_CONTROL_FOCUS,
);

/** The arrow after a row link's words, which steps forward while the row is hovered. */
export function RowLinkArrow() {
	return (
		<ArrowRightIcon
			className="size-3.5 shrink-0 transition-transform motion-safe:group-hover/row:translate-x-0.5"
			aria-hidden
		/>
	);
}

/**
 * The row's own link at its end: an `InlineLink` drawn as a button carrying `data-row-link` and no
 * handler of its own, so the press bubbles to the row, which owns the opening. A row given a real
 * link draws that instead, carrying the same mark, and the link opens itself. The link rule is
 * `InlineLink`'s, and the row's own hover is added since the whole row is the pointer path. The
 * accessible name is built from the visible words, so it always starts with what is on screen and
 * a reader who speaks them reaches the link (WCAG 2.2 SC 2.5.3). An `aria-label` rather than
 * hidden text after the words: how name-from-content joins an out-of-flow child to the text before
 * it differs between engines, and a label is one string everywhere.
 */
function RowLinkCell({ link }: { link: PracticeTableRowLink | undefined }) {
	return (
		<TableCell className="text-right">
			{link && (
				<InlineLink
					render={link.render ?? <button type="button" />}
					data-row-link
					aria-label={`${link.text} ${link.name}`}
					className={ROW_LINK_CLASS}
				>
					{link.text}
					<RowLinkArrow />
				</InlineLink>
			)}
		</TableCell>
	);
}
