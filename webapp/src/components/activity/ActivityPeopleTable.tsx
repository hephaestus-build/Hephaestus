import { PeopleIcon, SearchIcon } from "@primer/octicons-react";
import {
	createColumnHelper,
	FlexRender,
	functionalUpdate,
	type Row,
	type SortingState,
	useTable,
} from "@tanstack/react-table";
// oxlint-disable-next-line no-restricted-imports -- TanStack Table keys its column and row models on the identity of `columns`, `data` and controlled state, which the compiler memoises as an optimisation rather than a promise.
import { useMemo, useState } from "react";

import { cn } from "cn";
import type { ActivityPeople, ActivityPerson } from "@/api/types.gen";
import { type DataTableFeatures, dataTableFeatures } from "@/components/common/data-table";
import { DataTableHeader } from "@/components/common/DataTableHeader";
import { FacetMultiSelect } from "@/components/common/FacetMultiSelect";
import { FilterToolbar } from "@/components/common/FilterToolbar";
import { InfiniteListEnd } from "@/components/common/InfiniteListEnd";
import { InlineLink } from "@/components/common/InlineLink";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from "@/components/ui/empty";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableRow } from "@/components/ui/table";
import { artifactKindNoun, ARTIFACT_KIND } from "@/lib/artifact-kinds";
import type { ProviderType } from "@/lib/provider/provider-terms";
import { capitalise } from "@/lib/text";

import { type PeopleSort, PEOPLE_SORTS, personLevel } from "./activity-search";
import { STALE } from "./activity-tones";
import { ActivityEmpty } from "./ActivityEmpty";
import { ActivitySparkline } from "./ActivitySparkline";
import { MemberAvatar } from "./MemberAvatar";
import { competitionPositions } from "./people-positions";

/** The people once they are in, and whether they are the previous period's while another loads. */
export type ActivityPeopleState = PanelState<{ people: ActivityPeople; stale: boolean }>;

export interface PeopleOrder {
	sort: PeopleSort;
	desc: boolean;
}

export interface ActivityPeopleTableProps {
	state: ActivityPeopleState;
	providerType: ProviderType;
	order: PeopleOrder;
	onOrderChange: (order: PeopleOrder) => void;
	/** The repositories the table counts, by full path; none is every repository. */
	repo: readonly string[];
	onRepoChange: (repo: string[]) => void;
}

/** A step of rows to render at a time: enough to scroll through, few enough to render at once. */
const ROWS_STEP = 50;

const SKELETON_ROWS = 6;

/** "Zoë" and "ZOE" find "zoe": the search folds case and accents, as a name typed from memory does. */
const NAMES = new Intl.Collator(undefined, { sensitivity: "base" });

const fold = (text: string): string =>
	text
		.normalize("NFD")
		.replaceAll(/\p{Mn}/gu, "")
		.toLowerCase();

const columnHelper = createColumnHelper<DataTableFeatures, ActivityPerson>();

/**
 * Everyone who contributed in the scope, one row per person, sorted in the browser by any count.
 * Sorted by a count, each row shows its position, which standard competition ranking gives: two
 * people with the same count share it, and the next position skips (1, 2, 2, 4). A search hides
 * rows but keeps their positions. The rows render a step at a time as the end scrolls into view.
 */
export function ActivityPeopleTable({
	state,
	providerType,
	order,
	onOrderChange,
	repo,
	onRepoChange,
}: ActivityPeopleTableProps) {
	const [search, setSearch] = useState("");
	const [shown, setShown] = useState(ROWS_STEP);
	const people = state.status === "ready" ? state.people.people : undefined;
	const from = state.status === "ready" ? state.people.from : undefined;
	const to = state.status === "ready" ? state.people.to : undefined;
	const pullRequests = capitalise(artifactKindNoun(ARTIFACT_KIND.pullRequest, 2, providerType));

	// The table's `data`, in name order: the sorted row model keeps the data's order on a tie, so
	// people with the same count stay in name order in either direction.
	const data = useMemo(
		() => [...(people ?? [])].sort((a, b) => NAMES.compare(a.person.name, b.person.name)),
		[people],
	);
	// Over every row, before the search: a search must not renumber what it leaves.
	const positions = useMemo(() => competitionPositions(data, order.sort), [data, order.sort]);
	const sorting = useMemo<SortingState>(
		() => [{ id: order.sort, desc: order.desc }],
		[order.sort, order.desc],
	);
	const columns = useMemo(
		() =>
			columnHelper.columns([
				columnHelper.display({
					id: "position",
					header: () => (
						<>
							<span aria-hidden>#</span>
							<span className="sr-only">Position</span>
						</>
					),
					cell: ({ row }) => (
						<span className="text-muted-foreground tabular-nums">
							{positions.get(row.original.person.id)}
						</span>
					),
				}),
				columnHelper.accessor((row) => row.person.name, {
					id: "name",
					header: "Person",
					sortFn: (a, b) => NAMES.compare(a.original.person.name, b.original.person.name),
					cell: ({ row }) => <PersonCell person={row.original} />,
				}),
				columnHelper.accessor((row) => row.counts.contributions, {
					id: "contributions",
					header: "Contributions",
					cell: ({ getValue }) => <span className="font-medium tabular-nums">{getValue()}</span>,
				}),
				columnHelper.accessor((row) => row.counts.pullRequestsOpened, {
					id: "pull-requests",
					header: pullRequests,
					cell: ({ row }) => (
						<CountPair
							count={row.original.counts.pullRequestsOpened}
							detail={`${row.original.counts.pullRequestsMerged} merged`}
						/>
					),
				}),
				columnHelper.accessor((row) => row.counts.pullRequestsReviewed, {
					id: "reviews",
					header: "Reviews",
					cell: ({ row }) => (
						<CountPair
							count={row.original.counts.pullRequestsReviewed}
							detail={peopleHelped(row.original.counts.peopleHelped)}
						/>
					),
				}),
				columnHelper.accessor((row) => row.counts.issuesOpened, {
					id: "issues",
					header: "Issues",
					cell: ({ getValue }) => <span className="tabular-nums">{getValue()}</span>,
				}),
				columnHelper.accessor((row) => row.counts.activeWeeks, {
					id: "active-weeks",
					header: "Active weeks",
					cell: ({ getValue }) => <span className="tabular-nums">{getValue()}</span>,
				}),
				columnHelper.display({
					id: "trend",
					header: "Each week",
					cell: ({ row }) =>
						from && to && <ActivitySparkline weeks={row.original.weeks} span={{ from, to }} />,
				}),
			]),
		[positions, pullRequests, from, to],
	);

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
		globalFilterFn: (row: Row<DataTableFeatures, ActivityPerson>, _columnId, filterValue) => {
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

	if (state.status === "error") {
		return (
			<QueryErrorAlert
				error={state.error}
				title="We could not load people"
				onRetry={state.onRetry}
			/>
		);
	}
	const repositories = state.status === "ready" ? state.people.repositories : [];
	const toolbar = (
		<FilterToolbar hasFilter={repo.length > 0} onReset={() => onRepoChange([])}>
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
			{(repositories.length > 1 || repo.length > 0) && (
				<FacetMultiSelect
					title="Repository"
					options={repositories.map(({ key }) => ({ value: key, label: key }))}
					selected={repo}
					onChange={onRepoChange}
				/>
			)}
		</FilterToolbar>
	);
	if (state.status === "ready" && data.length === 0) {
		return (
			<div className="space-y-3">
				{toolbar}
				<ActivityEmpty icon={<PeopleIcon />} title="No contributions in this range" />
			</div>
		);
	}
	const stale = state.status === "ready" && state.stale;
	const found = table.getFilteredRowModel().rows.length;
	const columnCount = table.getVisibleLeafColumns().length;
	return (
		<div className="space-y-3">
			<div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
				{toolbar}
				{state.status === "ready" && (
					<p role="status" className="text-sm text-muted-foreground tabular-nums">
						{search.trim() === ""
							? `${data.length} ${data.length === 1 ? "person" : "people"}`
							: `${found} of ${data.length} people`}
					</p>
				)}
			</div>
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
										<Skeleton className="h-4 w-40" />
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
							{row.getVisibleCells().map((cell) => (
								<TableCell key={cell.id}>
									<FlexRender cell={cell} />
								</TableCell>
							))}
						</TableRow>
					))}
				</TableBody>
			</Table>
			<InfiniteListEnd
				hasMore={table.getCanNextPage()}
				isLoadingMore={false}
				onLoadMore={() => setShown((count) => count + ROWS_STEP)}
				moreLabel="Show more people"
				failedLabel=""
				loadingRow={null}
			/>
		</div>
	);
}

/**
 * The name is the row's link and its keyboard stop, stretched over the row so the whole row is the
 * pointer target.
 */
function PersonCell({ person }: { person: ActivityPerson }) {
	return (
		<div className="flex min-w-0 items-center gap-3">
			<MemberAvatar user={person.person} />
			<div className="min-w-0">
				<InlineLink
					render={<DetailStackLink entry={personLevel(person.person.login)} />}
					className="block truncate font-medium after:absolute after:inset-0"
				>
					{person.person.name}
				</InlineLink>
				<p className="truncate text-xs text-muted-foreground">{person.person.login}</p>
			</div>
		</div>
	);
}

/** "12 · 10 merged": the count the column sorts by, then what it holds. */
function CountPair({ count, detail }: { count: number; detail: string }) {
	return (
		<span className="whitespace-nowrap tabular-nums">
			{count}
			<span className="text-muted-foreground"> · {detail}</span>
		</span>
	);
}

function peopleHelped(count: number): string {
	return `${count} ${count === 1 ? "person" : "people"}`;
}
