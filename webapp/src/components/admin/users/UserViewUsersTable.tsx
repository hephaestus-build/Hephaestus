import { UsersIcon } from "lucide-react";

import type { UserViewUser } from "@/api/types.gen";
import { TableRowsSkeleton } from "@/components/admin/integrations/TableRowsSkeleton";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { TablePagination } from "@/components/common/TablePagination";
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

export type UserViewUsersState = PanelState<{ users: UserViewUser[]; totalPages: number }>;

export interface UserViewUsersTableProps {
	state: UserViewUsersState;
	page: number;
	onPageChange: (page: number) => void;
	onView: (user: UserViewUser) => void;
}

const SKELETON_COLUMNS = ["w-40", "w-28", null];

function accountLabel(user: UserViewUser): string {
	if (user.accountId == null) return "No linked account";
	return user.accountStatus === "ACTIVE" ? "Linked account" : "Account unavailable";
}

function UsersTableHeader() {
	return (
		<TableHeader>
			<TableRow>
				<TableHead>User</TableHead>
				<TableHead>Hephaestus account</TableHead>
				<TableHead className="text-right">
					<span className="sr-only">User view</span>
				</TableHead>
			</TableRow>
		</TableHeader>
	);
}

export function UserViewUsersTable({ state, page, onPageChange, onView }: UserViewUsersTableProps) {
	if (state.status === "loading") {
		return (
			<Table>
				<UsersTableHeader />
				<TableRowsSkeleton columns={SKELETON_COLUMNS} />
			</Table>
		);
	}
	if (state.status === "error") {
		return (
			<QueryErrorAlert
				error={state.error}
				title="Could not load workspace users"
				onRetry={state.onRetry}
			/>
		);
	}
	if (state.users.length === 0) {
		return (
			<Empty className="border border-dashed">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<UsersIcon />
					</EmptyMedia>
					<EmptyTitle>No users to view</EmptyTitle>
					<EmptyDescription>This workspace has no human members yet.</EmptyDescription>
				</EmptyHeader>
			</Empty>
		);
	}
	return (
		<div className="space-y-3">
			<Table>
				<UsersTableHeader />
				<TableBody>
					{state.users.map((user) => (
						<TableRow key={user.userId}>
							<TableCell>
								<span className="block font-medium">{user.name ?? user.login}</span>
								<span className="block text-muted-foreground">{user.login}</span>
							</TableCell>
							<TableCell>{accountLabel(user)}</TableCell>
							<TableCell className="text-right">
								<Button
									variant="outline"
									size="sm"
									aria-label={`View as user: ${user.login}`}
									onClick={() => onView(user)}
								>
									View as user
								</Button>
							</TableCell>
						</TableRow>
					))}
				</TableBody>
			</Table>
			<TablePagination
				className="justify-end"
				page={page}
				totalPages={state.totalPages}
				onPageChange={onPageChange}
			/>
		</div>
	);
}
