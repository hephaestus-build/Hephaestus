import {
	createColumnHelper,
	FlexRender,
	functionalUpdate,
	type Row,
	type SortingState,
	useTable,
} from "@tanstack/react-table";
import { SearchIcon } from "lucide-react";
// oxlint-disable-next-line no-restricted-imports -- TanStack Table keys its column and row models on the identity of `columns`, `data` and controlled state, which the compiler memoises as an optimisation rather than a promise.
import { type ReactElement, useMemo, useState } from "react";

import { cn } from "cn";
import type { ActivityPeople } from "@/api/types.gen";
import { type DataTableFeatures, dataTableFeatures } from "@/components/common/data-table";
import { DataTableHeader } from "@/components/common/DataTableHeader";
import { InfiniteListEnd } from "@/components/common/InfiniteListEnd";
import { InlineLink } from "@/components/common/InlineLink";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Badge } from "@/components/ui/badge";
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from "@/components/ui/empty";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { Table, TableBody, TableCell, TableRow } from "@/components/ui/table";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import type { ProviderType } from "@/lib/provider/provider-terms";
import { nameOrder } from "@/lib/text";
import type { RefreshFailure } from "@/runtime/tanstack-query/infinite-list";

import { NoneMark } from "./ActionChip";
import type { PeopleRow, PeopleRows } from "./activity-people-rows";
import { type PeopleSort, PEOPLE_SORTS } from "./activity-search";
import { STALE } from "./activity-tones";
import { ActivityCountCell, ActivityCountHeader, type CountedCategory } from "./ActivityCountCell";
import { ActivityEmpty, PEOPLE_EMPTY_ICON } from "./ActivityEmpty";
import { ActivitySparkline } from "./ActivitySparkline";
import { MemberAvatar } from "./MemberAvatar";
import { competitionPositions, PEOPLE_COUNTS } from "./people-positions";
import { PeopleSkeletonRows } from "./PeopleSkeletonRows";

/**
 * The people once they are in, whether they are the previous period's while another loads, and why
 * reading them again failed while they stay.
 */
export type PeopleTableState = PanelState<{
	people: PeopleRows;
	stale: boolean;
	refreshFailure?: RefreshFailure;
}>;

/** Workspace activity's people, with the automation and coverage the table leaves to its page. */
export type ActivityPeopleState = PanelState<{
	people: ActivityPeople;
	stale: boolean;
	refreshFailure?: RefreshFailure;
}>;

export interface PeopleOrder {
	sort: PeopleSort;
	desc: boolean;
}

export interface ActivityPeopleTableProps {
	state: PeopleTableState;
	providerType: ProviderType;
	order: PeopleOrder;
	onOrderChange: (order: PeopleOrder) => void;
	/** The repositories the table counts, by full path; none is every repository. */
	repo: readonly string[];
	/**
	 * Where a person's name leads, as the element the name renders through: a level over the page, or
	 * the person's page at the provider.
	 */
	personLink: (person: PeopleRow["person"]) => ReactElement;
}

/** A step of rows to render at a time: enough to scroll through, few enough to render at once. */
const ROWS_STEP = 50;

/** "Zoë" and "ZOE" find "zoe": the search folds case and accents, as a name typed from memory does. */
const fold = (text: string): string =>
	text
		.normalize("NFD")
		.replaceAll(/\p{Mn}/gu, "")
		.toLowerCase();

const columnHelper = createColumnHelper<DataTableFeatures, PeopleRow>();

/**
 * The person stays in place while the figures scroll; above `sm`, the position stays too. Above
 * the `z-10` chips and lines, so a figure scrolling under never paints over a name.
 */
const PINNED = "sticky z-20 bg-card";

/** Above `sm`, the position column's width is where the pinned person column starts. */
const POSITION_WIDTH = "w-12 min-w-12";

/** A number column fits its figures and leaves the rest of the row to the person. */
const NUMBER_COLUMN = { numeric: true, className: "w-px" };

/**
 * Everyone who contributed in the scope, one row per person, sorted in the browser by any count.
 * Sorted by a count, each row shows its position: two people with the same count share it, and the
 * next position skips (1, 2, 2, 4). A search hides rows but keeps their positions. The rows render a
 * step at a time as the end scrolls into view.
 */
export function ActivityPeopleTable({
	state,
	providerType,
	order,
	onOrderChange,
	repo,
	personLink,
}: ActivityPeopleTableProps) {
	const [search, setSearch] = useState("");
	const [shown, setShown] = useState(ROWS_STEP);
	const people = state.status === "ready" ? state.people.people : undefined;
	const from = state.status === "ready" ? state.people.from : undefined;
	const to = state.status === "ready" ? state.people.to : undefined;
	const firstContributorIds =
		state.status === "ready" ? state.people.highlights.firstContributors : undefined;

	// The table's `data`, in name order: the sorted row model keeps the data's order on a tie, so
	// people with the same count stay in name order in either direction.
	const data = useMemo(
		() => [...(people ?? [])].sort((a, b) => nameOrder.compare(a.person.name, b.person.name)),
		[people],
	);
	// Over every row, before the search: a search must not renumber what it leaves.
	const positions = useMemo(() => competitionPositions(data, order.sort), [data, order.sort]);
	const sorting = useMemo<SortingState>(
		() => [{ id: order.sort, desc: order.desc }],
		[order.sort, order.desc],
	);
	const columns = useMemo(() => {
		const firstContributors = new Set(firstContributorIds);
		const positioned = order.sort !== "name";
		const countColumn = (category: CountedCategory) =>
			columnHelper.accessor(PEOPLE_COUNTS[category], {
				id: category,
				header: () => <ActivityCountHeader category={category} providerType={providerType} />,
				meta: NUMBER_COLUMN,
				cell: ({ row }) => (
					<ActivityCountCell
						category={category}
						counts={row.original.counts}
						providerType={providerType}
					/>
				),
			});
		return columnHelper.columns([
			columnHelper.display({
				id: "position",
				header: () => (
					<>
						<span aria-hidden>#</span>
						<span className="sr-only">Position</span>
					</>
				),
				// Below `sm` the position gives its room to the figures; the order and its ties stay.
				meta: {
					numeric: true,
					className: cn(POSITION_WIDTH, "left-0 pl-3", PINNED, "hidden sm:table-cell"),
				},
				cell: ({ row }) => (
					<span className="text-muted-foreground">{positions.get(row.original.person.id)}</span>
				),
			}),
			columnHelper.accessor((row) => row.person.name, {
				id: "name" satisfies PeopleSort,
				header: "Person",
				sortDescFirst: false,
				sortFn: (a, b) => nameOrder.compare(a.original.person.name, b.original.person.name),
				meta: {
					// The edge marks where the figures pass under the pinned columns.
					className: cn("left-0 border-r", PINNED, positioned && "sm:left-12"),
				},
				cell: ({ row }) => (
					<PersonCell
						person={row.original.person}
						link={personLink}
						first={firstContributors.has(row.original.person.id)}
					/>
				),
			}),
			columnHelper.accessor(PEOPLE_COUNTS.contributions, {
				id: "contributions" satisfies PeopleSort,
				header: () => (
					<>
						<span className="sm:hidden">
							Total<span className="sr-only"> contributions</span>
						</span>
						<span className="hidden sm:inline">Contributions</span>
					</>
				),
				meta: NUMBER_COLUMN,
				cell: ({ getValue }) => <Figure count={getValue()} strong />,
			}),
			countColumn("pull-requests"),
			countColumn("reviews"),
			countColumn("issues"),
			columnHelper.accessor(PEOPLE_COUNTS["active-weeks"], {
				id: "active-weeks" satisfies PeopleSort,
				header: () => (
					<>
						<span aria-hidden>Weeks</span>
						<span className="sr-only">Active weeks</span>
					</>
				),
				meta: NUMBER_COLUMN,
				cell: ({ getValue }) => <Figure count={getValue()} />,
			}),
			columnHelper.display({
				id: "trend",
				header: () => (
					<>
						<span aria-hidden>Trend</span>
						<span className="sr-only">Weekly trend</span>
					</>
				),
				meta: { className: "w-px pr-3" },
				cell: ({ row }) =>
					from && to && <ActivitySparkline weeks={row.original.weeks} span={{ from, to }} />,
			}),
		]);
	}, [positions, providerType, from, to, firstContributorIds, order.sort, personLink]);

	const table = useTable({
		features: dataTableFeatures,
		data,
		columns,
		enableSortingRemoval: false,
		onSortingChange: (updater) => {
			const [next] = functionalUpdate(updater, sorting);
			const sort = PEOPLE_SORTS.find((candidate) => candidate === next?.id);
			if (next && sort) {
				onOrderChange({ sort, desc: next.desc });
			}
		},
		onGlobalFilterChange: (updater) => {
			const next: unknown = functionalUpdate(updater, search);
			setSearch(typeof next === "string" ? next : "");
		},
		globalFilterFn: (row: Row<DataTableFeatures, PeopleRow>, _columnId, filterValue) => {
			const needle: unknown = filterValue;
			const { name, login } = row.original.person;
			return (
				typeof needle === "string" &&
				[name, login].some((text) => fold(text).includes(fold(needle.trim())))
			);
		},
		state: {
			sorting,
			globalFilter: search,
			columnVisibility: { position: order.sort !== "name" },
			pagination: { pageIndex: 0, pageSize: shown },
		},
	});

	const toolbar = (
		<InputGroup className="w-full sm:w-64">
			<InputGroupAddon>
				<SearchIcon />
			</InputGroupAddon>
			<InputGroupInput
				type="search"
				placeholder="Search people"
				aria-label="Search people"
				value={search}
				onChange={(event) => table.setGlobalFilter(event.target.value)}
			/>
		</InputGroup>
	);
	const stale = state.status === "ready" && state.stale;
	const found = table.getFilteredRowModel().rows.length;
	const columnCount = table.getVisibleLeafColumns().length;
	const empty = state.status === "ready" && data.length === 0;
	const EmptyIcon = PEOPLE_EMPTY_ICON(providerType);
	// The toolbar and the count stay mounted through every state, so the count's live region exists
	// before its first words.
	return (
		<div className="space-y-3">
			<div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between sm:gap-4">
				{toolbar}
				<p role="status" className="text-sm text-muted-foreground tabular-nums">
					{state.status === "ready" && !empty && peopleCount(found, data.length, search)}
				</p>
			</div>
			{state.status === "error" && (
				<QueryErrorAlert
					error={state.error}
					title="We could not load people"
					onRetry={state.onRetry}
				/>
			)}
			{/* The people already read stay, but a reader must know they may be out of date. */}
			{state.status === "ready" && state.refreshFailure !== undefined && (
				<QueryErrorAlert
					error={state.refreshFailure.error}
					title="We could not refresh people"
					pending={state.refreshFailure.pending}
					onRetry={state.refreshFailure.onRetry}
				/>
			)}
			{empty && (
				<div aria-busy={stale || undefined} className={cn(stale && STALE)}>
					<ActivityEmpty
						icon={<EmptyIcon />}
						title={
							repo.length > 0
								? "No activity in these repositories in this range"
								: "No activity in this range"
						}
					/>
				</div>
			)}
			{state.status !== "error" && !empty && (
				<>
					<Table
						bordered
						aria-label="People"
						aria-busy={state.status === "loading" || stale || undefined}
						className={cn("sm:min-w-200", stale && STALE)}
						// Reserve the pinned identity columns in the browser's logical scroll region.
						containerClassName={cn(
							"scroll-ps-41",
							order.sort === "name" ? "sm:scroll-ps-61" : "sm:scroll-ps-73",
						)}
						// Native focus skips a button already inside the scrollport, even when a pinned cell covers it.
						onFocusCapture={(event) => {
							const { target } = event;
							if (!(target instanceof HTMLButtonElement)) {
								return;
							}
							const head = target.closest("th");
							if (head === null || getComputedStyle(head).position === "sticky") {
								return;
							}
							target.scrollIntoView({ block: "nearest", inline: "nearest", behavior: "instant" });
						}}
					>
						<DataTableHeader table={table} />
						<TableBody>
							{state.status === "loading" && <PeopleSkeletonRows colSpan={columnCount} />}
							{table.getRowModel().rows.map((row) => (
								<TableRow key={row.id} className="relative">
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
					</Table>
					{/* Keep the empty state outside the table's horizontal scroll. */}
					{state.status === "ready" && found === 0 && (
						<Empty>
							<EmptyHeader>
								<EmptyTitle>No one matches your search</EmptyTitle>
								<EmptyDescription>Try a different name.</EmptyDescription>
							</EmptyHeader>
						</Empty>
					)}
					<InfiniteListEnd
						hasMore={table.getCanNextPage()}
						onLoadMore={() => setShown((count) => count + ROWS_STEP)}
						moreLabel="Show more people"
					/>
				</>
			)}
		</div>
	);
}

/**
 * The name is the row's link and its keyboard stop, stretched over its pinned cell so the identity is
 * the pointer target. A person whose first contribution to the workspace is in the range is new here.
 *
 * The identity has a set width, so a long name wraps to two lines and is then cut, instead of widening
 * the pinned columns; the New marker sits on the login line, so it never takes room from the name.
 */
function PersonCell({
	person,
	link,
	first,
}: {
	person: PeopleRow["person"];
	link: ActivityPeopleTableProps["personLink"];
	first: boolean;
}) {
	return (
		<div className="flex w-36 items-center gap-3 sm:w-56">
			<MemberAvatar user={person} />
			<div className="min-w-0 flex-1">
				<InlineLink
					render={link(person)}
					className="line-clamp-2 font-medium break-words whitespace-normal after:absolute after:inset-0"
				>
					{person.name}
				</InlineLink>
				<p className="flex min-w-0 items-center gap-1.5 text-xs text-muted-foreground">
					<span className="truncate">{person.login}</span>
					{first && <NewBadge />}
				</p>
			</div>
		</div>
	);
}

const FIRST_CONTRIBUTION = "First contribution in this range";

/** Quiet, beside the name: someone to welcome, not a status. */
function NewBadge() {
	return (
		<Tooltip>
			<TooltipTrigger
				render={<Badge variant="muted" role="img" aria-label={FIRST_CONTRIBUTION} />}
				className="relative z-10 shrink-0"
			>
				<span aria-hidden>New</span>
			</TooltipTrigger>
			<TooltipContent>{FIRST_CONTRIBUTION}</TooltipContent>
		</Tooltip>
	);
}

/** One figure in a number column, or a dash for none: "17", "—". */
function Figure({ count, strong = false }: { count: number; strong?: boolean }) {
	if (count === 0) {
		return <NoneMark />;
	}
	return <span className={cn(strong && "font-semibold")}>{count.toLocaleString("en-GB")}</span>;
}

/** "12 people", or what a search leaves of them: "3 of 12 people". */
function peopleCount(found: number, total: number, search: string): string {
	if (search.trim() !== "") {
		return `${found} of ${total} people`;
	}
	return `${total} ${total === 1 ? "person" : "people"}`;
}
