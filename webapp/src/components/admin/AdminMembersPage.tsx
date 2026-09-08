import { BookUser } from "lucide-react";
import type { ComponentProps, ReactElement } from "react";

import type { TeamInfo } from "@/api/types.gen";
import {
	WorkspaceMembershipPanel,
	type WorkspaceMembershipPanelProps,
} from "@/components/admin/members/WorkspaceMembershipPanel";
import type { ExtendedUserTeams } from "@/components/admin/types";
import { UsersTable, type UsersTableView } from "@/components/admin/UsersTable";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/core/PageHeader";
import { PageLayout } from "@/components/core/PageLayout";

interface AdminMembersPageProps {
	accountMemberships?: WorkspaceMembershipPanelProps;
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
	accountMemberships,
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
				description="Manage account access and browse source-control contributions by team."
			/>
			{accountMemberships && <WorkspaceMembershipPanel {...accountMemberships} />}
			<h2 className="text-xl font-semibold">Source-control contributors</h2>
			<p className="text-sm text-muted-foreground">
				Contribution attribution and leaderboard visibility do not grant workspace access.
			</p>
			{error ? (
				<QueryErrorAlert error={error} title="Couldn't load members" onRetry={onRetry} />
			) : (
				<UsersTable
					users={users}
					teams={teams}
					isLoading={isLoading}
					onToggleHidden={onToggleHidden}
					view={view}
					onViewChange={onViewChange}
					renderPageLink={renderPageLink}
				/>
			)}
		</PageLayout>
	);
}
