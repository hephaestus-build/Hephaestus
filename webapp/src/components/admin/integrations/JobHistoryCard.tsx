import { Card, CardContent, CardHeader } from "@/components/ui/card";

import { IntegrationCardHeading } from "./IntegrationCardHeading";
import { SyncJobsTable, type SyncJobsTableProps } from "./SyncJobsTable";

/** "Sync history" card shell shared by the SCM, Slack and Outline integration routes. */
export function JobHistoryCard(props: SyncJobsTableProps) {
	return (
		<Card>
			<CardHeader>
				<IntegrationCardHeading>Sync history</IntegrationCardHeading>
			</CardHeader>
			<CardContent>
				<SyncJobsTable {...props} />
			</CardContent>
		</Card>
	);
}
