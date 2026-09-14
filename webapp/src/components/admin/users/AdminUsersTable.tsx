import { LogOut, MoreHorizontal, UserCog, Users } from "lucide-react";

import type { AdminAccountView } from "@/api/types.gen";
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

export interface AdminUsersTableProps {
	users: AdminAccountView[];
	isLoading: boolean;
	isError: boolean;
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
}

const COLUMN_COUNT = 6;

function roleBadgeVariant(appRole: string | undefined) {
	return appRole === "APP_ADMIN" ? "default" : "secondary";
}

// Active is neutral; suspended / being-deleted are non-normal states and read as destructive.
function statusBadgeVariant(status: string | undefined) {
	if (!status) return "outline" as const;
	const normalized = status.toUpperCase();
	if (normalized === "ACTIVE") return "secondary" as const;
	if (normalized === "SUSPENDED" || normalized === "DELETING" || normalized === "DELETED") {
		return "destructive" as const;
	}
	return "outline" as const;
}

export function AdminUsersTable({
	users,
	isLoading,
	isError,
	hasSearch,
	totalLoaded,
	currentUserId,
	hasNextPage,
	isFetchingNextPage,
	onLoadMore,
	onChangeRole,
	onForceSignOut,
}: AdminUsersTableProps) {
	return (
		<div className="space-y-4">
			<div className="rounded-md border">
				<Table aria-label="Application users">
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
					<TableBody>
						{isLoading ? (
							<TableRow>
								<TableCell colSpan={COLUMN_COUNT} className="h-32 text-center">
									<div className="flex flex-col items-center justify-center gap-2">
										<Spinner />
										<p className="text-sm text-muted-foreground">Loading users…</p>
									</div>
								</TableCell>
							</TableRow>
						) : isError ? (
							<TableRow>
								<TableCell colSpan={COLUMN_COUNT} className="h-32 text-center">
									<p className="text-sm text-destructive">
										Failed to load users. Please try again later.
									</p>
								</TableCell>
							</TableRow>
						) : users.length === 0 ? (
							<TableRow>
								<TableCell colSpan={COLUMN_COUNT} className="h-32 text-center">
									{hasSearch && (hasNextPage || isFetchingNextPage) ? (
										// Search filters loaded rows client-side, and the page is still loading more —
										// don't claim "no users" before every page is in (avoids a false negative).
										<div className="flex flex-col items-center justify-center gap-2">
											<Spinner aria-hidden />
											<p className="text-sm text-muted-foreground">Searching all users…</p>
										</div>
									) : (
										<div className="flex flex-col items-center justify-center gap-2">
											<Users className="size-8 text-muted-foreground" aria-hidden />
											<p className="text-sm font-medium">No users found</p>
											<p className="text-xs text-muted-foreground">
												{hasSearch ? "Try adjusting your search." : "No accounts exist yet."}
											</p>
										</div>
									)}
								</TableCell>
							</TableRow>
						) : (
							users.map((user) => {
								const isSelf = user.id != null && user.id === currentUserId;
								// You can't revoke your own admin — it would lock you out of /admin with no
								// in-app recovery (the server rejects it too; this just hides the footgun).
								const isSelfAdmin = isSelf && user.appRole === "APP_ADMIN";
								const name = user.displayName ?? "—";
								return (
									<TableRow key={user.id ?? user.primaryEmail}>
										<TableCell className="font-mono text-xs text-muted-foreground">
											{user.id ?? "—"}
										</TableCell>
										<TableCell className="font-medium">{name}</TableCell>
										<TableCell className="text-muted-foreground">
											{user.primaryEmail ?? "—"}
										</TableCell>
										<TableCell>
											<Badge variant={roleBadgeVariant(user.appRole)}>
												{user.appRole ?? "USER"}
											</Badge>
										</TableCell>
										<TableCell>
											{user.status ? (
												<Badge variant={statusBadgeVariant(user.status)}>{user.status}</Badge>
											) : (
												<span className="text-muted-foreground">—</span>
											)}
										</TableCell>
										<TableCell className="text-right">
											<DropdownMenu>
												<DropdownMenuTrigger
													render={
														<Button
															variant="ghost"
															size="icon-xs"
															aria-label={`Actions for ${name}`}
														/>
													}
												>
													<MoreHorizontal className="size-4" />
												</DropdownMenuTrigger>
												<DropdownMenuContent align="end">
													<DropdownMenuGroup>
														<DropdownMenuLabel>Account actions</DropdownMenuLabel>
														<DropdownMenuItem
															disabled={isSelfAdmin}
															onClick={() => !isSelfAdmin && onChangeRole(user)}
														>
															<UserCog className="size-4" />
															{isSelfAdmin
																? "Can't revoke your own admin"
																: user.appRole === "APP_ADMIN"
																	? "Revoke admin"
																	: "Change role"}
														</DropdownMenuItem>
														<DropdownMenuSeparator />
														<DropdownMenuItem
															variant="destructive"
															onClick={() => onForceSignOut(user)}
														>
															<LogOut className="size-4" />
															Force sign-out
														</DropdownMenuItem>
													</DropdownMenuGroup>
												</DropdownMenuContent>
											</DropdownMenu>
										</TableCell>
									</TableRow>
								);
							})
						)}
					</TableBody>
				</Table>
			</div>

			<div className="flex flex-col items-center justify-between gap-2 sm:flex-row">
				<p className="text-sm text-muted-foreground" aria-live="polite">
					Showing {users.length}
					{hasSearch ? ` of ${totalLoaded} loaded` : ""} user{users.length === 1 ? "" : "s"}
				</p>
				{hasNextPage && (
					<Button variant="outline" size="sm" onClick={onLoadMore} disabled={isFetchingNextPage}>
						{isFetchingNextPage ? <Spinner className="size-4" /> : null}
						Load more
					</Button>
				)}
			</div>
		</div>
	);
}
