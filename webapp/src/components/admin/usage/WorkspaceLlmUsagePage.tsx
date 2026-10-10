import { Link } from "@tanstack/react-router";
import { CircleDollarSign } from "lucide-react";

import type { WorkspaceLlmUsageReport } from "@/api/types.gen";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { StatTileSkeleton } from "@/components/common/StatTile";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
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
				<QueryErrorAlert
					error={view.error}
					title="We could not load AI usage"
					onRetry={view.onRetry}
				/>
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

/** The report's shape without its landmarks: a skeleton must not claim the regions the report will own. */
function UsageSkeleton() {
	return (
		<div className="space-y-8" aria-busy="true">
			<div className="space-y-3">
				<Skeleton className="h-7 w-40" />
				<div className="grid gap-4 md:grid-cols-2">
					{["shared", "provider"].map((slot) => (
						<StatTileSkeleton key={slot}>
							<Skeleton className="h-1.5 w-full" />
						</StatTileSkeleton>
					))}
				</div>
			</div>
			<div className="space-y-3">
				<Skeleton className="h-7 w-32" />
				<LlmUsageByJobTypeTable purses={["SHARED"]} />
			</div>
		</div>
	);
}
