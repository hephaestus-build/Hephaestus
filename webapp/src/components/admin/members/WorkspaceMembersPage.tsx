import { BookUser } from "lucide-react";
import type { ComponentProps, ReactElement } from "react";

import type { TeamInfo } from "@/api/types.gen";
import type { ExtendedUserTeams } from "@/components/admin/members/user-teams";
import {
	WorkspaceMembersTable,
	type WorkspaceMembersTableView,
} from "@/components/admin/members/WorkspaceMembersTable";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";

interface WorkspaceMembersPageProps {
	users: ExtendedUserTeams[];
	teams: TeamInfo[];
	isLoading: boolean;
	error?: unknown;
	onRetry?: () => void;
	onToggleHidden?: (userId: number, hidden: boolean) => void;
	view: WorkspaceMembersTableView;
	onViewChange: (patch: Partial<WorkspaceMembersTableView>) => void;
	renderPageLink: (page: number, props: ComponentProps<"a">) => ReactElement;
}

export function WorkspaceMembersPage({
	users,
	teams,
	isLoading,
	error,
	onRetry,
	onToggleHidden,
	view,
	onViewChange,
	renderPageLink,
}: WorkspaceMembersPageProps) {
	return (
		<PageLayout>
			<PageHeader
				icon={<BookUser />}
				title="Members"
				description="Browse workspace members and filter by team."
			/>
			{error == null ? (
				<WorkspaceMembersTable
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
