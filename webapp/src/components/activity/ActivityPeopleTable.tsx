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
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableRow } from "@/components/ui/table";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import type { ProviderType } from "@/lib/provider/provider-terms";
import { nameOrder } from "@/lib/text";

import { NoneMark } from "./ActionChip";
import type { PeopleRow, PeopleRows } from "./activity-people-rows";
import { type PeopleSort, PEOPLE_SORTS } from "./activity-search";
import { STALE } from "./activity-tones";
import { ActivityCountCell, ActivityCountHeader, type CountedCategory } from "./ActivityCountCell";
import { ActivityEmpty, PEOPLE_EMPTY_ICON } from "./ActivityEmpty";
import { ActivitySparkline } from "./ActivitySparkline";
import { MemberAvatar } from "./MemberAvatar";
import { competitionPositions, PEOPLE_COUNTS } from "./people-positions";

/** The people once they are in, and whether they are the previous period's while another loads. */
export type PeopleTableState = PanelState<{ people: PeopleRows; stale: boolean }>;

/** Workspace activity's people, with the automation and coverage the table leaves to its page. */
export type ActivityPeopleState = PanelState<{ people: ActivityPeople; stale: boolean }>;

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

const SKELETON_ROWS = 6;

/** "Zoë" and "ZOE" find "zoe": the search folds case and accents, as a name typed from memory does. */
const fold = (text: string): string =>
	text
		.normalize("NFD")
		.replaceAll(/\p{Mn}/gu, "")
		.toLowerCase();

const columnHelper = createColumnHelper<DataTableFeatures, PeopleRow>();

/**
 * Below `sm` the position and the person stay in place while the figures scroll under them, so a
 * narrow screen shows whose row it is and that there is more to the side.
 */
const PINNED = "max-sm:sticky max-sm:z-10 max-sm:bg-card";

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
				meta: { numeric: true, className: cn("w-10 min-w-10 pl-3 max-sm:left-0", PINNED) },
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
					className: cn(
						"min-w-40 sm:min-w-48",
						PINNED,
						positioned ? "max-sm:left-10" : "max-sm:left-0",
					),
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
				header: "Contributions",
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
				// The line shows what it is; its name is for a screen reader.
				header: () => <span className="sr-only">Weekly trend</span>,
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
						className={cn("min-w-200", stale && STALE)}
					>
						<DataTableHeader table={table} />
						<TableBody>
							{state.status === "loading" &&
								Array.from({ length: SKELETON_ROWS }, (_, index) => (
									<TableRow key={index} variant="static" aria-hidden>
										<TableCell colSpan={columnCount}>
											<div className="flex items-center gap-3">
												<Skeleton className="size-8 rounded-full" />
												<div className="space-y-1.5">
													<Skeleton className="h-4 w-40" />
													<Skeleton className="h-3 w-16" />
												</div>
												<Skeleton className="ml-auto h-4 w-1/2" />
											</div>
										</TableCell>
									</TableRow>
								))}
							{state.status === "ready" && found === 0 && (
								<TableRow variant="static">
									<TableCell colSpan={columnCount} className="p-4 whitespace-normal">
										<Empty>
											<EmptyHeader>
												<EmptyTitle>No one matches your search</EmptyTitle>
												<EmptyDescription>Try a different name.</EmptyDescription>
											</EmptyHeader>
										</Empty>
									</TableCell>
								</TableRow>
							)}
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
 * The name is the row's link and its keyboard stop, stretched over the row so the whole row is the
 * pointer target. A person whose first contribution to the workspace is in the range is new here.
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
		<div className="flex min-w-0 items-center gap-3">
			<MemberAvatar user={person} />
			<div className="min-w-0">
				<div className="flex min-w-0 items-center gap-2">
					<InlineLink
						render={link(person)}
						className="truncate font-medium after:absolute after:inset-0"
					>
						{person.name}
					</InlineLink>
					{first && <NewBadge />}
				</div>
				<p className="truncate text-xs text-muted-foreground">{person.login}</p>
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
				className="relative z-10"
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
