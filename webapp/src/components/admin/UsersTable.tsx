import {
	type ColumnVisibilityState,
	createColumnHelper,
	FlexRender,
	functionalUpdate,
	type SortingState,
	useTable,
} from "@tanstack/react-table";
import { ChevronDown, EyeIcon, EyeOffIcon, Filter, Search, Users } from "lucide-react";
// oxlint-disable-next-line no-restricted-imports -- TanStack Table keys its caches on identity, which the compiler memoises as an optimisation rather than promises; each `useMemo` below says what breaks without it.
import { type ComponentProps, type ReactElement, useEffect, useMemo, useState } from "react";

import type { TeamInfo } from "@/api/types.gen";
import { type DataTableFeatures, dataTableFeatures } from "@/components/common/data-table";
import { DataTableHeader } from "@/components/common/DataTableHeader";
import { TablePagination } from "@/components/common/TablePagination";
import { Button } from "@/components/ui/button";
import {
	DropdownMenu,
	DropdownMenuCheckboxItem,
	DropdownMenuContent,
	DropdownMenuGroup,
	DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { Label } from "@/components/ui/label";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { Spinner } from "@/components/ui/spinner";
import { Table, TableBody, TableCell, TableRow } from "@/components/ui/table";

import type { ExtendedUserTeams } from "./types";

const columnHelper = createColumnHelper<DataTableFeatures, ExtendedUserTeams>();

interface UsersTableProps {
	users: ExtendedUserTeams[];
	teams: TeamInfo[];
	isLoading?: boolean;
	onToggleHidden?: (userId: number, hidden: boolean) => void;
	view: UsersTableView;
	onViewChange: (patch: Partial<UsersTableView>) => void;
	renderPageLink?: (page: number, props: ComponentProps<"a">) => ReactElement;
}

export interface UsersTableView {
	q: string;
	team: string;
	sort: "name" | "username";
	desc: boolean;
	page: number;
	size: number;
}

export function UsersTable({
	users,
	teams,
	isLoading = false,
	onToggleHidden,
	view,
	onViewChange,
	renderPageLink,
}: UsersTableProps) {
	const [columnVisibility, setColumnVisibility] = useState<ColumnVisibilityState>({});
	// Identity matters as much as the value: `useTable` republishes controlled state on every render,
	// so a fresh array here writes the sorting atom every time the parent re-renders.
	const sorting = useMemo<SortingState>(
		() => [{ id: view.sort, desc: view.desc }],
		[view.sort, view.desc],
	);

	// TanStack Table caches its column model — and every row model derived from it — against the
	// identity of this array, so a fresh one each render rebuilds all of them.
	const columns = useMemo(
		() =>
			columnHelper.columns([
				columnHelper.accessor((row) => row.user.name, {
					id: "name",
					header: "Name",
					cell: ({ row }) => <div className="font-medium">{row.original.user.name}</div>,
				}),
				columnHelper.accessor((row) => row.user.login, {
					id: "username",
					header: "Username",
					cell: ({ row }) => <div className="text-muted-foreground">{row.original.user.login}</div>,
				}),
				...(onToggleHidden
					? [
							columnHelper.display({
								id: "visibility",
								// Hiding a row's own controls is not a useful affordance.
								enableHiding: false,
								header: () => "Visible",
								cell: ({ row }) => (
									<Button
										variant="ghost"
										size="icon-xs"
										onClick={() =>
											onToggleHidden(Number(row.original.user.id), !row.original.hidden)
										}
										aria-label={
											row.original.hidden
												? `Show ${row.original.user.name}`
												: `Hide ${row.original.user.name}`
										}
									>
										{row.original.hidden ? (
											<EyeOffIcon className="size-4 text-muted-foreground" />
										) : (
											<EyeIcon className="size-4" />
										)}
									</Button>
								),
							}),
						]
					: []),
			]),
		[onToggleHidden],
	);

	// The table's `data`, keyed the same way `columns` is: the core row model is cached against this
	// array's identity.
	const filteredData = useMemo(
		() =>
			users.filter((user) => {
				if (view.team === "all") return true;
				return user.teams.some((team) => team.id.toString() === view.team);
			}),
		[users, view.team],
	);

	const sortedTeams = [...teams].sort((a, b) => a.name.localeCompare(b.name));

	const teamFilterItems = [
		{ value: "all", label: "All teams" },
		...sortedTeams.map((team) => ({ value: team.id.toString(), label: team.name })),
	];

	const pageSizeItems = [10, 20, 30, 40, 50].map((size) => ({
		value: `${size}`,
		label: `${size}`,
	}));

	// Every controlled slice gets its own change handler: v9 republishes `options.state` on each
	// render, so a slice supplied without one is frozen — the table's setter writes it, the next
	// render overwrites it.
	const table = useTable({
		features: dataTableFeatures,
		data: filteredData,
		columns,
		autoResetPageIndex: false,
		onSortingChange: (updater) => {
			const [next] = functionalUpdate(updater, sorting);
			onViewChange({
				sort: next?.id === "username" ? "username" : "name",
				desc: next?.desc ?? false,
				page: 0,
			});
		},
		onColumnVisibilityChange: setColumnVisibility,
		onGlobalFilterChange: (updater) => {
			// The library types `globalFilter` as `any`, so the value is narrowed on the way back out
			// rather than trusted into a typed search param.
			const next: unknown = functionalUpdate(updater, view.q.trim());
			onViewChange({ q: typeof next === "string" ? next : "", page: 0 });
		},
		onPaginationChange: (updater) => {
			const next = functionalUpdate(updater, { pageIndex: view.page, pageSize: view.size });
			if (next.pageIndex !== view.page || next.pageSize !== view.size) {
				onViewChange({ page: next.pageIndex, size: next.pageSize });
			}
		},
		state: {
			sorting,
			columnVisibility,
			globalFilter: view.q.trim(),
			pagination: { pageIndex: view.page, pageSize: view.size },
		},
	});
	const lastPage = Math.max(0, table.getPageCount() - 1);

	useEffect(() => {
		if (!isLoading && view.page > lastPage) onViewChange({ page: lastPage });
	}, [isLoading, lastPage, onViewChange, view.page]);

	return (
		<div className="w-full space-y-4">
			<div className="flex flex-col items-start justify-between gap-4 sm:flex-row sm:items-center">
				<div className="flex w-full flex-col items-start space-y-2 sm:w-auto sm:flex-row sm:items-center sm:space-y-0 sm:space-x-3">
					<InputGroup className="w-full sm:w-auto">
						<InputGroupAddon>
							<Search />
						</InputGroupAddon>
						<InputGroupInput
							placeholder="Search by name or username..."
							value={view.q}
							onChange={(event) => onViewChange({ q: event.target.value, page: 0 })}
							className="w-full sm:w-[300px]"
						/>
					</InputGroup>
					<Select
						value={view.team}
						onValueChange={(value) => value && onViewChange({ team: value, page: 0 })}
						items={teamFilterItems}
					>
						<SelectTrigger
							id="member-team-filter"
							className="w-full sm:w-[200px]"
							aria-label="Filter members by team"
						>
							<Filter className="mr-2 h-4 w-4" />
							<SelectValue placeholder="Filter by team" />
						</SelectTrigger>
						<SelectContent aria-label="Filter members by team">
							<SelectItem value="all">
								<div className="flex items-center space-x-2">
									<div className="h-3 w-3 rounded-full bg-muted" />
									<span>All teams</span>
								</div>
							</SelectItem>
							{sortedTeams.map((team) => (
								<SelectItem key={team.id} value={team.id.toString()}>
									<div className="flex items-center space-x-2">
										<span>{team.name}</span>
									</div>
								</SelectItem>
							))}
						</SelectContent>
					</Select>
				</div>
				<div className="flex items-center space-x-2">
					{view.q && (
						<Button
							variant="ghost"
							size="sm"
							onClick={() => onViewChange({ q: "", page: 0 })}
							className="h-8"
						>
							Clear search
						</Button>
					)}
					<DropdownMenu>
						<DropdownMenuTrigger
							render={<Button variant="outline" size="sm" className="ml-auto" />}
						>
							Columns <ChevronDown className="ml-2 h-4 w-4" />
						</DropdownMenuTrigger>
						<DropdownMenuContent align="end">
							<DropdownMenuGroup>
								{table
									.getAllColumns()
									.filter((column) => column.getCanHide())
									.map((column) => {
										return (
											<DropdownMenuCheckboxItem
												key={column.id}
												className="capitalize"
												checked={column.getIsVisible()}
												onCheckedChange={(value) => column.toggleVisibility(value)}
											>
												{column.id}
											</DropdownMenuCheckboxItem>
										);
									})}
							</DropdownMenuGroup>
						</DropdownMenuContent>
					</DropdownMenu>
				</div>
			</div>

			<Table bordered>
				<DataTableHeader table={table} />
				<TableBody>
					{isLoading ? (
						<TableRow>
							<TableCell
								colSpan={table.getVisibleLeafColumns().length}
								className="h-32 text-center"
							>
								<div className="flex flex-col items-center justify-center space-y-2">
									<Spinner />
									<p className="text-sm text-muted-foreground">Loading users...</p>
								</div>
							</TableCell>
						</TableRow>
					) : table.getRowModel().rows.length > 0 ? (
						table.getRowModel().rows.map((row) => (
							<TableRow key={row.id}>
								{row.getVisibleCells().map((cell) => (
									<TableCell key={cell.id}>
										<FlexRender cell={cell} />
									</TableCell>
								))}
							</TableRow>
						))
					) : (
						<TableRow>
							<TableCell
								colSpan={table.getVisibleLeafColumns().length}
								className="h-32 text-center"
							>
								<div className="flex flex-col items-center justify-center space-y-2">
									<Users className="h-8 w-8 text-muted-foreground" />
									<p className="text-sm font-medium">No users found</p>
									<p className="text-xs text-muted-foreground">
										{view.q || view.team !== "all"
											? "Try adjusting your search or filter criteria"
											: "No users have been added to the workspace yet"}
									</p>
								</div>
							</TableCell>
						</TableRow>
					)}
				</TableBody>
			</Table>

			<div className="flex flex-col items-center justify-between space-y-4 py-4 sm:flex-row sm:space-y-0 sm:space-x-2">
				<div className="order-2 flex-1 text-sm text-muted-foreground sm:order-1">
					<div className="flex flex-col gap-1 sm:flex-row sm:gap-4">
						<span>
							Showing {table.getRowModel().rows.length} of {table.getRowCount()} users
						</span>
					</div>
				</div>
				<div className="order-1 flex flex-col items-center space-y-4 sm:order-2 sm:flex-row sm:space-y-0 sm:space-x-6 lg:space-x-8">
					<div className="flex items-center space-x-2">
						<Label
							id="member-rows-per-page-label"
							htmlFor="member-rows-per-page"
							className="whitespace-nowrap"
						>
							Rows per page
						</Label>
						<Select
							value={`${table.state.pagination.pageSize}`}
							onValueChange={(value) => {
								table.setPageSize(Number(value));
							}}
							items={pageSizeItems}
						>
							<SelectTrigger id="member-rows-per-page" className="h-8 w-[70px]">
								<SelectValue />
							</SelectTrigger>
							<SelectContent side="top" aria-labelledby="member-rows-per-page-label">
								{[10, 20, 30, 40, 50].map((pageSize) => (
									<SelectItem key={pageSize} value={`${pageSize}`}>
										{pageSize}
									</SelectItem>
								))}
							</SelectContent>
						</Select>
					</div>

					<TablePagination
						page={table.state.pagination.pageIndex}
						totalPages={table.getPageCount()}
						{...(renderPageLink
							? { renderPageLink }
							: { onPageChange: (page: number) => table.setPageIndex(page) })}
					/>
				</div>
			</div>
		</div>
	);
}
