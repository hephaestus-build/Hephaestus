import { Ban, LogOut, MoreHorizontal, UserCheck, UserCog, Users } from "lucide-react";
import type { AdminAccountView } from "@/api/types.gen";
import { LOADING_MORE_ROWS } from "@/components/common/InfiniteListEnd";
import { QueryErrorAlert, type QueryErrorAlertProps } from "@/components/common/QueryErrorAlert";
import { TableRowsSkeleton } from "@/components/common/TableRowsSkeleton";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
	DropdownMenu,
	DropdownMenuContent,
	DropdownMenuGroup,
	DropdownMenuItem,
	DropdownMenuLabel,
	DropdownMenuSeparator,
	DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Spinner } from "@/components/ui/spinner";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import type { MorePages } from "@/runtime/tanstack-query/infinite-list";

import { ACCOUNT_STATUS_LABELS, APP_ROLE_LABELS, type AppRole } from "./account-labels";

export interface AdminUsersTableProps extends Pick<MorePages, "loadMoreError" | "refreshFailure"> {
	users: AdminAccountView[];
	isLoading: boolean;
	/** Why no users could be read at all; a refusal offers no retry, an outage does. */
	loadFailure?: Pick<QueryErrorAlertProps, "error" | "onRetry" | "pending">;
	/** Whether a search term is currently narrowing the visible rows. */
	hasSearch: boolean;
	/** Total rows loaded from the server (before client-side search filtering). */
	totalLoaded: number;
	/** Id of the signed-in admin, who cannot revoke their own admin role. Undefined until loaded. */
	currentUserId: number | undefined;
	hasNextPage: boolean;
	isFetchingNextPage: boolean;
	onLoadMore: () => void;
	onChangeRole: (user: AdminAccountView) => void;
	onForceSignOut: (user: AdminAccountView) => void;
	/** Asks to block an active account or to unblock a blocked one. */
	onChangeStatus: (user: AdminAccountView) => void;
}

const COLUMN_COUNT = 6;

/** ID, name, email, role, status, and the action slot, which has nothing to promise. */
const SKELETON_COLUMNS = ["w-8", "w-32", "w-44", "w-24", "w-16", null];

function roleBadgeVariant(appRole: AppRole) {
	return appRole === "APP_ADMIN" ? "default" : "secondary";
}

function statusBadgeVariant(status: AdminAccountView["status"]) {
	if (status === "ACTIVE") {
		return "secondary";
	}
	return status === "UNCLAIMED" ? "outline" : "destructive";
}

function changeRoleLabel(isSelfAdmin: boolean, appRole: AppRole) {
	if (isSelfAdmin) {
		return "You cannot revoke your own instance admin role";
	}
	return appRole === "APP_ADMIN" ? "Revoke instance admin" : "Grant instance admin";
}

// Shown once loading has stopped, so a search with pages left unread has not searched them all.
function emptyTitle(hasSearch: boolean, hasNextPage: boolean) {
	if (!hasSearch) {
		return "No users yet";
	}
	return hasNextPage ? "No matches among the loaded users" : "No users found";
}

export function AdminUsersTable({
	totalLoaded,
	onLoadMore,
	loadFailure,
	...rows
}: AdminUsersTableProps) {
	const {
		users,
		isLoading,
		hasSearch,
		hasNextPage,
		isFetchingNextPage,
		loadMoreError,
		refreshFailure,
	} = rows;
	if (loadFailure !== undefined) {
		return <QueryErrorAlert title="We could not load users" {...loadFailure} />;
	}
	// With no row shown, a search that reads on says so in the table's one row instead.
	const loadingMore = !isLoading && isFetchingNextPage && users.length > 0;
	return (
		<div className="space-y-4">
			{refreshFailure !== undefined && (
				<QueryErrorAlert title="These users may be out of date" {...refreshFailure} />
			)}
			<Table
				bordered
				aria-label="Application users"
				aria-busy={isLoading || isFetchingNextPage || undefined}
			>
				<TableHeader>
					<TableRow>
						<TableHead scope="col">ID</TableHead>
						<TableHead scope="col">Name</TableHead>
						<TableHead scope="col">Email</TableHead>
						<TableHead scope="col">Role</TableHead>
						<TableHead scope="col">Status</TableHead>
						<TableHead scope="col" className="text-right">
							<span className="sr-only">Actions</span>
						</TableHead>
					</TableRow>
				</TableHeader>
				{isLoading ? (
					<TableRowsSkeleton columns={SKELETON_COLUMNS} />
				) : (
					<TableBody>
						<UserRows {...rows} />
					</TableBody>
				)}
				{loadingMore && (
					<TableRowsSkeleton columns={SKELETON_COLUMNS} rows={LOADING_MORE_ROWS} continues />
				)}
			</Table>

			<div className="flex flex-col items-center justify-between gap-2 sm:flex-row">
				<p className="text-sm text-muted-foreground" aria-live="polite">
					{/* No count until the first page is in: "0 users" while loading would read as a result. */}
					{!isLoading && (
						<>
							Showing {users.length}
							{hasSearch ? ` of ${totalLoaded} loaded` : ""}{" "}
							{(hasSearch ? totalLoaded : users.length) === 1 ? "user" : "users"}
						</>
					)}
				</p>
				{hasNextPage && loadMoreError === undefined && (
					<Button variant="outline" size="sm" onClick={onLoadMore} disabled={isFetchingNextPage}>
						{isFetchingNextPage ? <Spinner className="size-4" /> : null}
						Load more
					</Button>
				)}
			</div>
			{loadMoreError !== undefined && (
				<QueryErrorAlert
					title={hasSearch ? "Search results may be incomplete" : "We could not load more users"}
					error={loadMoreError}
					onRetry={onLoadMore}
				/>
			)}
		</div>
	);
}

function UserRows({
	users,
	hasSearch,
	currentUserId,
	hasNextPage,
	isFetchingNextPage,
	loadMoreError,
	refreshFailure,
	onChangeRole,
	onForceSignOut,
	onChangeStatus,
}: Omit<AdminUsersTableProps, "totalLoaded" | "onLoadMore" | "isLoading" | "loadFailure">) {
	if (users.length === 0) {
		// A failed read stops the search from loading more, so it must not keep promising results.
		const stopped = loadMoreError !== undefined || refreshFailure !== undefined;
		return (
			<TableRow>
				<TableCell colSpan={COLUMN_COUNT} className="h-32 text-center">
					{hasSearch && (isFetchingNextPage || (hasNextPage && !stopped)) ? (
						// Search filters loaded rows client-side, and the page is still loading more —
						// don't claim "no users" before every page is in (avoids a false negative).
						<div className="flex flex-col items-center justify-center gap-2">
							<Spinner aria-hidden />
							<p className="text-sm text-muted-foreground">Searching all users…</p>
						</div>
					) : (
						<div className="flex flex-col items-center justify-center gap-2">
							<Users className="size-8 text-muted-foreground" aria-hidden />
							<p className="text-sm font-medium">{emptyTitle(hasSearch, hasNextPage)}</p>
							{hasSearch ? (
								<p className="text-xs text-muted-foreground">
									{hasNextPage
										? "Some users did not load, so the search did not include them."
										: "Try a different search."}
								</p>
							) : null}
						</div>
					)}
				</TableCell>
			</TableRow>
		);
	}
	return users.map((user) => {
		const isSelf = user.id != null && user.id === currentUserId;
		// You can't revoke your own admin — it would lock you out of /admin with no
		// in-app recovery (the server rejects it too; this just hides the footgun).
		const isSelfAdmin = isSelf && user.appRole === "APP_ADMIN";
		const name = user.displayName ?? "—";
		return (
			<TableRow key={user.id ?? user.primaryEmail}>
				<TableCell className="font-mono text-xs text-muted-foreground">{user.id ?? "—"}</TableCell>
				<TableCell className="font-medium">{name}</TableCell>
				<TableCell className="text-muted-foreground">{user.primaryEmail ?? "—"}</TableCell>
				<TableCell>
					<Badge variant={roleBadgeVariant(user.appRole)}>{APP_ROLE_LABELS[user.appRole]}</Badge>
				</TableCell>
				<TableCell>
					<Badge variant={statusBadgeVariant(user.status)}>
						{ACCOUNT_STATUS_LABELS[user.status]}
					</Badge>
				</TableCell>
				<TableCell className="text-right">
					<DropdownMenu>
						<DropdownMenuTrigger
							render={<Button variant="ghost" size="icon-xs" aria-label={`Actions for ${name}`} />}
						>
							<MoreHorizontal className="size-4" />
						</DropdownMenuTrigger>
						<DropdownMenuContent align="end">
							<DropdownMenuGroup>
								<DropdownMenuLabel>Account actions</DropdownMenuLabel>
								<DropdownMenuItem
									disabled={isSelfAdmin}
									onClick={() => {
										if (!isSelfAdmin) {
											onChangeRole(user);
										}
									}}
								>
									<UserCog className="size-4" />
									{changeRoleLabel(isSelfAdmin, user.appRole)}
								</DropdownMenuItem>
								<DropdownMenuSeparator />
								<DropdownMenuItem variant="destructive" onClick={() => onForceSignOut(user)}>
									<LogOut className="size-4" />
									Force sign out
								</DropdownMenuItem>
								{/* An admin never blocks themselves, so the action is hidden, not disabled. */}
								{user.status === "ACTIVE" && !isSelf && (
									<DropdownMenuItem variant="destructive" onClick={() => onChangeStatus(user)}>
										<Ban className="size-4" />
										Block account
									</DropdownMenuItem>
								)}
								{user.status === "BLOCKED" && (
									<DropdownMenuItem onClick={() => onChangeStatus(user)}>
										<UserCheck className="size-4" />
										Unblock account
									</DropdownMenuItem>
								)}
							</DropdownMenuGroup>
						</DropdownMenuContent>
					</DropdownMenu>
				</TableCell>
			</TableRow>
		);
	});
}
