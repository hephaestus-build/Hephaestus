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
import { type ComponentProps, type ReactElement, useMemo, useRef, useState } from "react";
import { useSpinDelay } from "spin-delay";
import { TableRowsSkeleton } from "@/components/common/TableRowsSkeleton";
import { Spinner } from "@/components/ui/spinner";

import type { TeamInfo } from "@/api/types.gen";
import { ACCOUNT_STATUS_LABELS } from "@/components/admin/users/account-labels";
import { type DataTableFeatures, dataTableFeatures } from "@/components/common/data-table";
import { DataTableHeader } from "@/components/common/DataTableHeader";
import { StatusBadge } from "@/components/common/StatusBadge";
import { TablePagination } from "@/components/common/TablePagination";
import {
	MEMBER_PUBLIC_ACTIVITY_DEFS,
	memberPublicActivity,
} from "@/components/practice-vocabulary/member-public-activity-defs";
import { StatusTooltip } from "@/components/practice-vocabulary/StatusTooltip";
import { Badge } from "@/components/ui/badge";
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
import { Table, TableBody, TableCell, TableRow } from "@/components/ui/table";
import { formatDate } from "@/lib/dates";
import { getProviderLabel } from "@/lib/provider/provider-labels";
import { hasText } from "@/lib/text";
import type { WorkspaceRole } from "@/lib/workspace-roles";

import { WORKSPACE_ROLE_LABELS, type WorkspaceMemberRow } from "./workspace-members";

const columnHelper = createColumnHelper<DataTableFeatures, WorkspaceMemberRow>();

const ALL_ROLES: readonly WorkspaceRole[] = ["OWNER", "ADMIN", "MEMBER"];

/** The width each column's placeholder promises, by column id; a column the reader hid has none. */
const SKELETON_WIDTHS: Record<string, string> = {
	name: "w-32",
	username: "w-24",
	account: "w-16",
	role: "w-20",
	joined: "w-20",
	"review scope": "w-16",
	"public activity": "w-24",
	visibility: "w-6",
};

interface WorkspaceMembersTableProps {
	members: WorkspaceMemberRow[];
	rowCount: number;
	teams: TeamInfo[];
	isLoading?: boolean;
	/**
	 * Previous rows stay readable while the next answer loads. Hide their count until it arrives,
	 * and announce a wait only if it lasts longer than a second.
	 */
	updating?: boolean;
	onToggleHidden?: (accountId: string, hidden: boolean) => void;
	/**
	 * Present for an admin, who can give a member another workspace role. `trigger` is the row's Role
	 * menu, where focus returns after a confirmation that the change asks for.
	 */
	onRoleChange?: (member: WorkspaceMemberRow, role: WorkspaceRole, trigger: HTMLElement) => void;
	/**
	 * The roles the reader may give and take. Only an owner manages owners, so for an admin an
	 * owner's row is read-only and Owner is not offered.
	 */
	assignableRoles?: readonly WorkspaceRole[];
	/** The member whose role change is in flight; only that member's role control waits. */
	pendingRoleAccountId?: string;
	view: WorkspaceMembersTableView;
	onViewChange: (patch: Partial<WorkspaceMembersTableView>) => void;
	renderPageLink?: (page: number, props: ComponentProps<"a">) => ReactElement;
}

export interface WorkspaceMembersTableView {
	q: string;
	team: string;
	sort: "name" | "username";
	desc: boolean;
	page: number;
	size: number;
}

interface MemberRoleSelectProps {
	member: WorkspaceMemberRow;
	items: { value: WorkspaceRole; label: string }[];
	pending: boolean;
	onRoleChange: NonNullable<WorkspaceMembersTableProps["onRoleChange"]>;
}

function MemberRoleSelect({ member, items, pending, onRoleChange }: MemberRoleSelectProps) {
	const trigger = useRef<HTMLButtonElement>(null);
	const { role } = member.membership;
	return (
		<Select
			value={role}
			items={items}
			disabled={pending}
			onValueChange={(next) => {
				if (next !== null && next !== role && trigger.current !== null) {
					onRoleChange(member, next, trigger.current);
				}
			}}
		>
			<SelectTrigger ref={trigger} size="sm" aria-label={`Role of ${member.name}`}>
				{pending && <Spinner />}
				<SelectValue />
			</SelectTrigger>
			<SelectContent aria-label={`Role of ${member.name}`}>
				{items.map((item) => (
					<SelectItem key={item.value} value={item.value}>
						{item.label}
					</SelectItem>
				))}
			</SelectContent>
		</Select>
	);
}

/**
 * Whether a member still has to answer the public activity question. A member who answered draws no
 * badge, and the answer itself is never shown.
 */
function PublicActivityStatus({ row }: { row: WorkspaceMemberRow }) {
	if (row.membership.publicActivityAnswered === null) {
		return null;
	}
	const status = memberPublicActivity(row.membership);
	if (status === null) {
		return <span className="sr-only">Answered</span>;
	}
	const def = MEMBER_PUBLIC_ACTIVITY_DEFS[status];
	return (
		<StatusTooltip
			openOnTap
			def={def}
			render={
				<StatusBadge
					def={def}
					render={<button type="button" />}
					aria-label={`${def.label}: ${row.name}`}
					className="cursor-help"
				/>
			}
		/>
	);
}

// An unclaimed account has not signed in; it does not indicate a fault.
function AccountStatus({ row }: { row: WorkspaceMemberRow }) {
	const { account, leftAt } = row.membership;
	if (leftAt !== null) {
		return <span className="text-muted-foreground">Left {formatDate(leftAt)}</span>;
	}
	if (account.status === "ACTIVE") {
		return <span className="text-muted-foreground">{ACCOUNT_STATUS_LABELS.ACTIVE}</span>;
	}
	return (
		<Badge variant={account.status === "UNCLAIMED" ? "outline" : "destructive"}>
			{ACCOUNT_STATUS_LABELS[account.status]}
		</Badge>
	);
}

export function WorkspaceMembersTable({
	members,
	rowCount,
	teams,
	isLoading = false,
	updating = false,
	onToggleHidden,
	onRoleChange,
	assignableRoles = ALL_ROLES,
	pendingRoleAccountId,
	view,
	onViewChange,
	renderPageLink,
}: WorkspaceMembersTableProps) {
	const showUpdating = useSpinDelay(updating, { delay: 1000, minDuration: 0 });
	// The server answers for a workspace admin, and only where the workspace publishes public activity.
	const showsPublicActivity = members.some(
		({ membership }) => membership.publicActivityAnswered !== null,
	);
	const [columnVisibility, setColumnVisibility] = useState<ColumnVisibilityState>({});
	// Identity matters as much as the value: `useTable` republishes controlled state on every render,
	// so a fresh array here writes the sorting atom every time the parent re-renders.
	const sorting = useMemo<SortingState>(
		() => [{ id: view.sort, desc: view.desc }],
		[view.sort, view.desc],
	);

	// TanStack Table caches its column model — and every row model derived from it — against the
	// identity of this array, so a fresh one each render rebuilds all of them.
	const columns = useMemo(() => {
		const roleItems = assignableRoles.map((role) => ({
			value: role,
			label: WORKSPACE_ROLE_LABELS[role],
		}));
		return columnHelper.columns([
			columnHelper.accessor((row) => row.name, {
				id: "name",
				header: "Name",
				cell: ({ row }) => <div className="font-medium">{row.original.name}</div>,
			}),
			columnHelper.accessor((row) => row.logins.join(" "), {
				id: "username",
				header: "Username",
				cell: ({ row }) => (
					<ul className="space-y-0.5">
						{row.original.membership.account.identities
							.filter((identity) => hasText(identity.login))
							.map((identity) => (
								<li key={`${identity.provider}:${identity.login ?? ""}`}>
									{identity.login}{" "}
									<span className="text-muted-foreground">
										({getProviderLabel(identity.provider)})
									</span>
								</li>
							))}
					</ul>
				),
			}),
			columnHelper.display({
				id: "account",
				header: () => "Account",
				cell: ({ row }) => <AccountStatus row={row.original} />,
			}),
			columnHelper.display({
				id: "role",
				header: () => "Role",
				cell: ({ row }) => {
					const { account, role } = row.original.membership;
					if (!onRoleChange || !assignableRoles.includes(role)) {
						return WORKSPACE_ROLE_LABELS[role];
					}
					return (
						<MemberRoleSelect
							member={row.original}
							items={roleItems}
							pending={pendingRoleAccountId === account.id}
							onRoleChange={onRoleChange}
						/>
					);
				},
			}),
			columnHelper.display({
				id: "joined",
				header: () => "Joined",
				cell: ({ row }) => (
					<span className="text-muted-foreground">
						{formatDate(row.original.membership.joinedAt)}
					</span>
				),
			}),
			columnHelper.display({
				id: "review scope",
				header: () => "Review scope",
				cell: ({ row }) => (
					<span className="text-muted-foreground">
						{row.original.membership.reviewSelected ? "Included" : "Excluded"}
					</span>
				),
			}),
			...(showsPublicActivity
				? [
						columnHelper.display({
							id: "public activity",
							header: () => "Public activity",
							cell: ({ row }) => <PublicActivityStatus row={row.original} />,
						}),
					]
				: []),
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
										onToggleHidden(
											row.original.membership.account.id,
											!row.original.membership.hidden,
										)
									}
									aria-label={
										row.original.membership.hidden
											? `Show ${row.original.name} in workspace activity`
											: `Hide ${row.original.name} from workspace activity`
									}
								>
									{row.original.membership.hidden ? (
										<EyeOffIcon className="size-4 text-muted-foreground" />
									) : (
										<EyeIcon className="size-4" />
									)}
								</Button>
							),
						}),
					]
				: []),
		]);
	}, [onToggleHidden, onRoleChange, assignableRoles, pendingRoleAccountId, showsPublicActivity]);

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
		data: members,
		rowCount,
		manualFiltering: true,
		manualSorting: true,
		manualPagination: true,
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
		onPaginationChange: (updater) => {
			const next = functionalUpdate(updater, { pageIndex: view.page, pageSize: view.size });
			if (next.pageIndex !== view.page || next.pageSize !== view.size) {
				onViewChange({ page: next.pageIndex, size: next.pageSize });
			}
		},
		state: {
			sorting,
			columnVisibility,
			pagination: { pageIndex: view.page, pageSize: view.size },
		},
	});

	const noMatchesMessage =
		hasText(view.q) || view.team !== "all"
			? "Try a different search or team."
			: "This workspace has no members yet.";
	const emptyMessage = rowCount > 0 ? "Choose another page." : noMatchesMessage;

	let body: ReactElement | ReactElement[];
	if (table.getRowModel().rows.length > 0) {
		body = table.getRowModel().rows.map((row) => (
			<TableRow key={row.id}>
				{row.getVisibleCells().map((cell) => (
					<TableCell key={cell.id}>
						<FlexRender cell={cell} />
					</TableCell>
				))}
			</TableRow>
		));
	} else {
		body = (
			<TableRow>
				<TableCell colSpan={table.getVisibleLeafColumns().length} className="h-32 text-center">
					<div className="flex flex-col items-center justify-center space-y-2">
						<Users className="h-8 w-8 text-muted-foreground" />
						<p className="text-sm font-medium">
							{rowCount > 0 ? "No members on this page" : "No members found"}
						</p>
						<p className="text-xs text-muted-foreground">{emptyMessage}</p>
						{rowCount > 0 && view.page > 0 && (
							<Button variant="outline" size="sm" onClick={() => onViewChange({ page: 0 })}>
								Go to first page
							</Button>
						)}
					</div>
				</TableCell>
			</TableRow>
		);
	}

	return (
		<div className="w-full space-y-4">
			<div className="flex flex-col items-start justify-between gap-4 sm:flex-row sm:flex-wrap sm:items-center">
				<div className="flex w-full flex-col items-start gap-2 sm:w-auto sm:flex-row sm:flex-wrap sm:items-center sm:gap-3">
					<InputGroup className="w-full sm:w-auto">
						<InputGroupAddon>
							<Search />
						</InputGroupAddon>
						<InputGroupInput
							placeholder="Search by name or username…"
							value={view.q}
							onChange={(event) => onViewChange({ q: event.target.value, page: 0 })}
							className="w-full sm:w-[300px]"
						/>
					</InputGroup>
					<Select
						value={view.team}
						onValueChange={(value) => {
							if (hasText(value)) {
								onViewChange({ team: value, page: 0 });
							}
						}}
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
									.map((column) => (
										<DropdownMenuCheckboxItem
											key={column.id}
											className="capitalize"
											checked={column.getIsVisible()}
											onCheckedChange={(value) => column.toggleVisibility(value)}
										>
											{column.id}
										</DropdownMenuCheckboxItem>
									))}
							</DropdownMenuGroup>
						</DropdownMenuContent>
					</DropdownMenu>
				</div>
			</div>

			<Table bordered aria-label="Workspace members" aria-busy={isLoading || updating || undefined}>
				<DataTableHeader table={table} />
				{isLoading ? (
					// A page of placeholder rows under the real header, one cell per visible column.
					<TableRowsSkeleton
						columns={table
							.getVisibleLeafColumns()
							.map((column) => SKELETON_WIDTHS[column.id] ?? null)}
						rows={view.size}
					/>
				) : (
					<TableBody>{body}</TableBody>
				)}
			</Table>

			<div className="flex flex-col items-center justify-between space-y-4 py-4 sm:flex-row sm:space-y-0 sm:space-x-2">
				<div role="status" className="order-2 flex-1 text-sm text-muted-foreground sm:order-1">
					{/* No count until the members are in: "0 of 0" while loading would read as a result. */}
					{!isLoading && (
						<div className="flex flex-col gap-1 sm:flex-row sm:gap-4">
							<span>
								{updating ? (
									showUpdating && "Updating members…"
								) : (
									<>
										Showing {table.getRowModel().rows.length} of {table.getRowCount()}{" "}
										{table.getRowCount() === 1 ? "member" : "members"}
									</>
								)}
							</span>
						</div>
					)}
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
