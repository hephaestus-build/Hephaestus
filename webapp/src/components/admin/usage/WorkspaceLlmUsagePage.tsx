import { Link } from "@tanstack/react-router";
import { CircleDollarSign } from "lucide-react";

import type { WorkspaceLlmUsageReport } from "@/api/types.gen";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

import { LlmUsageByJobTypeTable } from "./LlmUsageBreakdownTables";
import { MonthNavigator } from "./MonthNavigator";
import { WorkspaceUsageReport } from "./WorkspaceUsageReport";

export type UsageView = PanelState<{ report: WorkspaceLlmUsageReport }>;

export interface WorkspaceLlmUsagePageProps {
	month: string;
	isCurrentMonth: boolean;
	canGoNext: boolean;
	workspaceSlug: string;
	view: UsageView;
	onEditOwnProviderCap: () => void;
	/** The instant the projection is measured against. */
	now: Date;
}

export function WorkspaceLlmUsagePage({
	month,
	isCurrentMonth,
	canGoNext,
	workspaceSlug,
	view,
	onEditOwnProviderCap,
	now,
}: WorkspaceLlmUsagePageProps) {
	return (
		<PageLayout>
			<PageHeader
				icon={<CircleDollarSign />}
				title="AI usage"
				description="Track model spend and usage for this workspace."
				actions={
					<MonthNavigator
						month={month}
						canGoNext={canGoNext}
						renderMonthLink={(nextMonth, props) => (
							<Link
								{...props}
								to="/w/$workspaceSlug/admin/usage"
								params={{ workspaceSlug }}
								search={(previous) => ({ ...previous, month: nextMonth })}
							/>
						)}
					/>
				}
			/>

			{view.status === "error" && (
				<QueryErrorAlert error={view.error} title="Couldn't load AI usage" onRetry={view.onRetry} />
			)}
			{view.status === "loading" && <UsageSkeleton />}
			{view.status === "ready" && (
				<WorkspaceUsageReport
					report={view.report}
					month={month}
					isCurrentMonth={isCurrentMonth}
					workspaceSlug={workspaceSlug}
					onEditOwnProviderCap={onEditOwnProviderCap}
					now={now}
				/>
			)}
		</PageLayout>
	);
}

function UsageSkeleton() {
	return (
		<div className="space-y-6" aria-busy="true">
			<div className="grid gap-4 md:grid-cols-2">
				{["shared", "provider"].map((slot) => (
					<Card key={slot}>
						<CardHeader>
							<Skeleton className="h-4 w-40" />
							<Skeleton className="h-7 w-28" />
						</CardHeader>
						<CardContent>
							<Skeleton className="h-1.5 w-full" />
						</CardContent>
					</Card>
				))}
			</div>
			<Card>
				<CardHeader>
					<CardTitle>By run type</CardTitle>
				</CardHeader>
				<CardContent>
					<LlmUsageByJobTypeTable />
				</CardContent>
			</Card>
		</div>
	);
}
