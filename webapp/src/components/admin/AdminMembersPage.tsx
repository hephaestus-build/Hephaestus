import { BookUser } from "lucide-react";
import type { ComponentProps, ReactElement } from "react";

import type { TeamInfo } from "@/api/types.gen";
import type { ExtendedUserTeams } from "@/components/admin/types";
import { UsersTable, type UsersTableView } from "@/components/admin/UsersTable";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";

interface AdminMembersPageProps {
	users: ExtendedUserTeams[];
	teams: TeamInfo[];
	isLoading: boolean;
	error?: unknown;
	onRetry?: () => void;
	onToggleHidden?: (userId: number, hidden: boolean) => void;
	view: UsersTableView;
	onViewChange: (patch: Partial<UsersTableView>) => void;
	renderPageLink: (page: number, props: ComponentProps<"a">) => ReactElement;
}

export function AdminMembersPage({
	users,
	teams,
	isLoading,
	error,
	onRetry,
	onToggleHidden,
	view,
	onViewChange,
	renderPageLink,
}: AdminMembersPageProps) {
	return (
		<PageLayout>
			<PageHeader
				icon={<BookUser />}
				title="Members"
				description="Browse workspace members and filter by team."
			/>
			{error == null ? (
				<UsersTable
					users={users}
					teams={teams}
					isLoading={isLoading}
					onToggleHidden={onToggleHidden}
					view={view}
					onViewChange={onViewChange}
					renderPageLink={renderPageLink}
				/>
			) : (
				<QueryErrorAlert error={error} title="Couldn't load members" onRetry={onRetry} />
			)}
		</PageLayout>
	);
}
