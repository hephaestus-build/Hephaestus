import { Link } from "@tanstack/react-router";
import { Building2, Settings, Users } from "lucide-react";

import type { AdminWorkspaceView } from "@/api/types.gen";
import { QueryErrorAlert, type QueryErrorAlertProps } from "@/components/common/QueryErrorAlert";
import { TableRowsSkeleton } from "@/components/common/TableRowsSkeleton";
import { Badge } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import { asDate, formatDate } from "@/lib/dates";
import { getProviderLabel } from "@/lib/provider/provider-labels";
import type { RefreshFailure } from "@/runtime/tanstack-query/infinite-list";

export interface AdminWorkspacesTableProps {
	workspaces: AdminWorkspaceView[];
	isLoading: boolean;
	/** The list could not be read: a refusal, or a first read that failed. No workspace is listed. */
	loadFailure?: Pick<QueryErrorAlertProps, "error" | "onRetry" | "pending">;
	/** Reading the list again failed; the workspaces last read stay, with the failure above them. */
	refreshFailure?: RefreshFailure;
	hasSearch: boolean;
	onViewAsUser: (workspace: AdminWorkspaceView) => void;
}

type WorkspaceStatus = AdminWorkspaceView["status"];

/**
 * A workspace's status in words, keyed on the wire's enum so a status the server adds fails the build
 * here rather than reaching an admin as a constant name.
 */
const WORKSPACE_STATUS_LABELS: Record<WorkspaceStatus, string> = {
	ACTIVE: "Active",
	SUSPENDED: "Suspended",
	PURGED: "Being deleted",
};

function statusVariant(status: WorkspaceStatus): "secondary" | "destructive" {
	return status === "ACTIVE" ? "secondary" : "destructive";
}

function formatCreatedAt(value: AdminWorkspaceView["createdAt"]): string {
	const date = asDate(value);
	return date ? formatDate(date) : "–";
}

const SKELETON_COLUMNS = ["w-28", "w-24", "w-16", "w-14", "w-20", "w-8", "w-20", null];

function WorkspacesTableHeader() {
	return (
		<TableHeader>
			<TableRow>
				<TableHead scope="col">Name</TableHead>
				<TableHead scope="col">Slug</TableHead>
				<TableHead scope="col">Status</TableHead>
				<TableHead scope="col">Provider</TableHead>
				<TableHead scope="col">Owner</TableHead>
				<TableHead scope="col" className="text-right">
					Members
				</TableHead>
				<TableHead scope="col">Created</TableHead>
				<TableHead scope="col" className="text-right">
					<span className="sr-only">Actions</span>
				</TableHead>
			</TableRow>
		</TableHeader>
	);
}

/**
 * Metadata only: a workspace's content is reached through its administration or a user view, never
 * listed here.
 */
export function AdminWorkspacesTable({
	workspaces,
	isLoading,
	loadFailure,
	refreshFailure,
	hasSearch,
	onViewAsUser,
}: AdminWorkspacesTableProps) {
	if (loadFailure !== undefined) {
		return <QueryErrorAlert title="We could not load workspaces" {...loadFailure} />;
	}
	if (isLoading) {
		return (
			<Table bordered>
				<WorkspacesTableHeader />
				<TableRowsSkeleton columns={SKELETON_COLUMNS} />
			</Table>
		);
	}
	const refreshAlert = refreshFailure !== undefined && (
		<QueryErrorAlert title="These workspaces may be out of date" {...refreshFailure} />
	);
	if (workspaces.length === 0) {
		return (
			<>
				{refreshAlert}
				<div className="flex flex-col items-center gap-2 py-12 text-center text-muted-foreground">
					<Building2 className="size-8" aria-hidden />
					<p className="text-sm">
						{hasSearch ? "No workspaces match your search" : "No workspaces yet"}
					</p>
				</div>
			</>
		);
	}

	return (
		<>
			{refreshAlert}
			<Table bordered>
				<WorkspacesTableHeader />
				<TableBody>
					{workspaces.map((ws) => (
						<TableRow key={ws.id}>
							<TableCell className="font-medium">{ws.displayName}</TableCell>
							<TableCell className="font-mono text-xs text-muted-foreground">
								{ws.workspaceSlug}
							</TableCell>
							<TableCell>
								<Badge variant={statusVariant(ws.status)}>
									{WORKSPACE_STATUS_LABELS[ws.status]}
								</Badge>
							</TableCell>
							<TableCell>
								{ws.providerType ? (
									<Badge variant="outline">
										{getProviderLabel(ws.providerType, "Another provider")}
									</Badge>
								) : (
									<span className="text-muted-foreground">—</span>
								)}
							</TableCell>
							<TableCell className="text-muted-foreground">{ws.ownerLogin ?? "—"}</TableCell>
							<TableCell numeric className="text-right">
								{ws.memberCount}
							</TableCell>
							<TableCell className="text-sm whitespace-nowrap text-muted-foreground">
								{formatCreatedAt(ws.createdAt)}
							</TableCell>
							<TableCell className="text-right">
								{ws.status === "ACTIVE" ? (
									<div className="flex justify-end gap-2">
										<Link
											to="/w/$workspaceSlug/admin/settings"
											params={{ workspaceSlug: ws.workspaceSlug }}
											aria-label={`Open administration of ${ws.displayName}`}
											className={buttonVariants({ variant: "outline", size: "sm" })}
										>
											<Settings aria-hidden />
											Open administration
										</Link>
										<Button
											variant="outline"
											size="sm"
											aria-label={`View as user in ${ws.displayName}`}
											onClick={() => onViewAsUser(ws)}
										>
											<Users aria-hidden />
											View as user
										</Button>
									</div>
								) : (
									// The server answers no administration or user-view read of a workspace that is not active.
									<span className="text-sm text-muted-foreground">Available when active</span>
								)}
							</TableCell>
						</TableRow>
					))}
				</TableBody>
			</Table>
		</>
	);
}
