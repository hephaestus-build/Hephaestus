import { Card, CardContent, CardHeader } from "@/components/ui/card";

import { WorkspaceIntegrationCardHeading } from "./WorkspaceIntegrationCardHeading";
import { WorkspaceSyncJobsTable, type WorkspaceSyncJobsTableProps } from "./WorkspaceSyncJobsTable";

/** "Sync history" card shell shared by the SCM, Slack and Outline integration routes. */
export function WorkspaceJobHistoryCard(props: WorkspaceSyncJobsTableProps) {
	return (
		<Card>
			<CardHeader>
				<WorkspaceIntegrationCardHeading>Sync history</WorkspaceIntegrationCardHeading>
			</CardHeader>
			<CardContent>
				<WorkspaceSyncJobsTable {...props} />
			</CardContent>
		</Card>
	);
}
