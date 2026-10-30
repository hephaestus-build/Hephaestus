import { Link } from "@tanstack/react-router";
import { BookUser } from "lucide-react";
import type { ComponentProps, ReactElement } from "react";

import type { TeamInfo } from "@/api/types.gen";
import type { WorkspaceMemberRow } from "@/components/admin/members/workspace-members";
import {
	WorkspaceMembersTable,
	type WorkspaceMembersTableView,
} from "@/components/admin/members/WorkspaceMembersTable";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import type { WorkspaceRole } from "@/lib/workspace-roles";

interface WorkspaceMembersPageProps {
	workspaceSlug: string;
	members: WorkspaceMemberRow[];
	rowCount: number;
	teams: TeamInfo[];
	isLoading: boolean;
	/** The rows shown are the previous search's while this one loads. */
	updating?: boolean;
	error?: unknown;
	onRetry?: () => void;
	onToggleHidden?: (accountId: string, hidden: boolean) => void;
	onRoleChange?: (member: WorkspaceMemberRow, role: WorkspaceRole, trigger: HTMLElement) => void;
	assignableRoles?: readonly WorkspaceRole[];
	pendingRoleAccountId?: string;
	teamsError?: unknown;
	teamsRetrying?: boolean;
	onRetryTeams?: () => void;
	view: WorkspaceMembersTableView;
	onViewChange: (patch: Partial<WorkspaceMembersTableView>) => void;
	renderPageLink: (page: number, props: ComponentProps<"a">) => ReactElement;
}

export function WorkspaceMembersPage({
	workspaceSlug,
	members,
	rowCount,
	teams,
	isLoading,
	updating,
	error,
	onRetry,
	teamsError,
	teamsRetrying = false,
	onRetryTeams,
	onToggleHidden,
	onRoleChange,
	assignableRoles,
	pendingRoleAccountId,
	view,
	onViewChange,
	renderPageLink,
}: WorkspaceMembersPageProps) {
	return (
		<PageLayout>
			<PageHeader
				icon={<BookUser />}
				title="Members"
				description={
					<>
						Browse workspace members, filter by team, and change their roles. Review scope shows
						whose work Hephaestus reviews.{" "}
						<Link
							to="/w/$workspaceSlug/admin/practices/review"
							params={{ workspaceSlug }}
							search={{ section: "automated-review" }}
							hash="review-scope-heading"
							className="underline underline-offset-4"
						>
							Change in review settings
						</Link>
					</>
				}
			/>
			{error == null ? (
				<div className="space-y-4">
					{teamsError != null && (
						<QueryErrorAlert
							error={teamsError}
							title="We could not load teams"
							onRetry={onRetryTeams}
							pending={teamsRetrying}
						/>
					)}
					<WorkspaceMembersTable
						members={members}
						rowCount={rowCount}
						teams={teams}
						isLoading={isLoading}
						updating={updating}
						onToggleHidden={onToggleHidden}
						onRoleChange={onRoleChange}
						assignableRoles={assignableRoles}
						pendingRoleAccountId={pendingRoleAccountId}
						view={view}
						onViewChange={onViewChange}
						renderPageLink={renderPageLink}
					/>
				</div>
			) : (
				<QueryErrorAlert error={error} title="We could not load members" onRetry={onRetry} />
			)}
		</PageLayout>
	);
}
