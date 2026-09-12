import { Building2, Users } from "lucide-react";

import type { AdminWorkspaceView } from "@/api/types.gen";
import { TableRowsSkeleton } from "@/components/admin/integrations/TableRowsSkeleton";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import { asDate } from "@/lib/dates";

export interface AdminWorkspacesTableProps {
	workspaces: AdminWorkspaceView[];
	isLoading: boolean;
	isError: boolean;
	hasSearch: boolean;
	onViewUsers: (workspace: AdminWorkspaceView) => void;
}

function statusVariant(status: string): "secondary" | "destructive" | "outline" {
	if (status === "ACTIVE") return "secondary";
	if (status === "SUSPENDED" || status === "PURGED") return "destructive";
	return "outline";
}

function formatDate(value: AdminWorkspaceView["createdAt"]): string {
	return asDate(value)?.toLocaleDateString() ?? "–";
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
					<span className="sr-only">Support access</span>
				</TableHead>
			</TableRow>
		</TableHeader>
	);
}

/** Metadata only: a workspace's content is reached through a user view, never listed here. */
export function AdminWorkspacesTable({
	workspaces,
	isLoading,
	isError,
	hasSearch,
	onViewUsers,
}: AdminWorkspacesTableProps) {
	if (isError) {
		return (
			<p className="py-8 text-center text-sm text-destructive">
				Failed to load workspaces. Please try again.
			</p>
		);
	}
	if (isLoading) {
		return (
			<div className="rounded-md border">
				<Table>
					<WorkspacesTableHeader />
					<TableRowsSkeleton columns={SKELETON_COLUMNS} />
				</Table>
			</div>
		);
	}
	if (workspaces.length === 0) {
		return (
			<div className="flex flex-col items-center gap-2 py-12 text-center text-muted-foreground">
				<Building2 className="size-8" aria-hidden />
				<p className="text-sm">{hasSearch ? "No matching workspaces." : "No workspaces yet."}</p>
			</div>
		);
	}

	return (
		<div className="rounded-md border">
			<Table>
				<WorkspacesTableHeader />
				<TableBody>
					{workspaces.map((ws) => (
						<TableRow key={ws.id}>
							<TableCell className="font-medium">{ws.displayName}</TableCell>
							<TableCell className="font-mono text-xs text-muted-foreground">
								{ws.workspaceSlug}
							</TableCell>
							<TableCell>
								<Badge variant={statusVariant(ws.status)}>{ws.status}</Badge>
							</TableCell>
							<TableCell>
								{ws.providerType ? (
									<Badge variant="outline" className="text-xs">
										{ws.providerType}
									</Badge>
								) : (
									<span className="text-muted-foreground">—</span>
								)}
							</TableCell>
							<TableCell className="text-muted-foreground">{ws.ownerLogin ?? "—"}</TableCell>
							<TableCell className="text-right tabular-nums">{ws.memberCount}</TableCell>
							<TableCell className="whitespace-nowrap text-sm text-muted-foreground">
								{formatDate(ws.createdAt)}
							</TableCell>
							<TableCell className="text-right">
								<Button
									variant="outline"
									size="sm"
									aria-label={`View users of ${ws.displayName}`}
									disabled={ws.status !== "ACTIVE"}
									onClick={() => onViewUsers(ws)}
								>
									<Users aria-hidden />
									View users
								</Button>
								{ws.status !== "ACTIVE" && (
									<span className="sr-only">
										This workspace is {ws.status.toLowerCase()}, so its users cannot be viewed.
									</span>
								)}
							</TableCell>
						</TableRow>
					))}
				</TableBody>
			</Table>
		</div>
	);
}
