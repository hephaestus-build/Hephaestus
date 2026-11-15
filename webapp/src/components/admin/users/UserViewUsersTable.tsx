import { UsersIcon } from "lucide-react";

import type { UserViewAccount } from "@/api/types.gen";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { TablePagination } from "@/components/common/TablePagination";
import { TableRowsSkeleton } from "@/components/common/TableRowsSkeleton";
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
import { hasText } from "@/lib/text";

import { accountStatusLabel, accountName } from "./account-labels";

export type UserViewUsersState = PanelState<{ users: UserViewAccount[]; totalPages: number }>;

export interface UserViewUsersTableProps {
	state: UserViewUsersState;
	page: number;
	onPageChange: (page: number) => void;
	onView: (user: UserViewAccount) => void;
}

const SKELETON_COLUMNS = ["w-40", "w-28", null];

function UsersTableHeader() {
	return (
		<TableHeader>
			<TableRow>
				<TableHead scope="col">User</TableHead>
				<TableHead scope="col">Account</TableHead>
				<TableHead scope="col" className="text-right">
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
				title="We could not load workspace users"
				onRetry={state.onRetry}
			/>
		);
	}
	if (state.users.length === 0) {
		return (
			<Empty variant="outlined">
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
					{state.users.map((user) => {
						const name = accountName(user.account);
						return (
							<TableRow key={user.accountId}>
								<TableCell>
									<span className="block font-medium">{name}</span>
									{hasText(user.providerLogin) && user.providerLogin !== name && (
										<span className="block text-muted-foreground">{user.providerLogin}</span>
									)}
								</TableCell>
								<TableCell>{accountStatusLabel(user.account.status)}</TableCell>
								<TableCell className="text-right">
									<Button
										variant="outline"
										size="sm"
										aria-label={`View as user: ${name}`}
										onClick={() => onView(user)}
									>
										View as user
									</Button>
								</TableCell>
							</TableRow>
						);
					})}
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
