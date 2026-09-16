import { Link } from "@tanstack/react-router";
import { CircleDollarSign } from "lucide-react";

import type { WorkspaceLlmUsageReport } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

import { AdminLlmUsageReport } from "./AdminLlmUsageReport";
import { LlmUsageByJobTypeTable } from "./LlmUsageBreakdownTables";
import { MonthNavigator } from "./MonthNavigator";

export interface AdminLlmUsagePageProps {
	month: string;
	isCurrentMonth: boolean;
	canGoNext: boolean;
	workspaceSlug: string;
	report?: WorkspaceLlmUsageReport;
	isLoading: boolean;
	error: unknown;
	onRetry?: () => void;
	onEditOwnProviderCap: () => void;
	/** The instant the projection is measured against. */
	now: Date;
}

type UsageView =
	| { state: "loading" }
	| { state: "error"; error: unknown }
	| { state: "ready"; report: WorkspaceLlmUsageReport };

function viewOf(error: unknown, isLoading: boolean, report?: WorkspaceLlmUsageReport): UsageView {
	if (error != null) {
		return { state: "error", error };
	}
	if (isLoading || report == null) {
		return { state: "loading" };
	}
	return { state: "ready", report };
}

export function AdminLlmUsagePage({
	month,
	isCurrentMonth,
	canGoNext,
	workspaceSlug,
	report,
	isLoading,
	error,
	onRetry,
	onEditOwnProviderCap,
	now,
}: AdminLlmUsagePageProps) {
	const view = viewOf(error, isLoading, report);
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

			{view.state === "error" && (
				<QueryErrorAlert error={view.error} title="Couldn't load AI usage" onRetry={onRetry} />
			)}
			{view.state === "loading" && <UsageSkeleton />}
			{view.state === "ready" && (
				<AdminLlmUsageReport
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
		<>
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
		</>
	);
}
