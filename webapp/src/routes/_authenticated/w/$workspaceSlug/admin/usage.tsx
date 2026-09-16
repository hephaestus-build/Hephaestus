import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, retainSearchParams } from "@tanstack/react-router";
import { useRef, useState } from "react";
import { toast } from "sonner";

import {
	getLlmUsageReportOptions,
	getLlmUsageReportQueryKey,
	updateWorkspaceLlmBudgetMutation,
} from "@/api/@tanstack/react-query.gen";
import { AdminLlmUsagePage } from "@/components/admin/usage/AdminLlmUsagePage";
import { SetOwnProviderBudgetDialog } from "@/components/admin/usage/SetOwnProviderBudgetDialog";
import {
	monthOf,
	USAGE_SEARCH_PARAMS,
	usageSearchSchema,
} from "@/components/admin/usage/usage-search";
import { canStepForwardFrom, isCurrentMonthUtc } from "@/components/admin/usage/usage-utils";
import { useNow } from "@/components/common/use-now";
import { workspaceAdminHead } from "@/lib/page-title";
import { problemDetailOf } from "@/lib/problem-detail";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/usage")({
	head: workspaceAdminHead("AI usage"),
	component: AdminUsageContainer,
	validateSearch: usageSearchSchema,
	search: { middlewares: [retainSearchParams(USAGE_SEARCH_PARAMS)] },
});

function AdminUsageContainer() {
	const queryClient = useQueryClient();
	const { workspaceSlug } = Route.useParams();
	const month = monthOf(Route.useSearch());
	const [isEditingOwnProviderCap, setIsEditingOwnProviderCap] = useState(false);
	const isCapDialogOnScreenRef = useRef(false);
	const editOwnProviderCap = (isEditing: boolean) => {
		isCapDialogOnScreenRef.current = isEditing;
		setIsEditingOwnProviderCap(isEditing);
	};

	const {
		data: report,
		isLoading,
		error,
		refetch,
	} = useQuery({
		...getLlmUsageReportOptions({ path: { workspaceSlug }, query: { month } }),
		placeholderData: keepPreviousData,
	});

	const updateOwnProviderCap = useMutation({
		...updateWorkspaceLlmBudgetMutation(),
		onSuccess: (_data, variables) => {
			void queryClient.invalidateQueries({
				queryKey: getLlmUsageReportQueryKey({ path: { workspaceSlug } }),
			});
			toast.success(
				variables.body.monthlyBudgetUsd == null
					? "Cap removed. New calls resume within a minute."
					: "Cap saved. New calls resume within a minute.",
			);
			editOwnProviderCap(false);
		},
		onError: (saveError) => {
			if (!isCapDialogOnScreenRef.current) {
				toast.error("Couldn't save the cap", { description: problemDetailOf(saveError) });
			}
		},
	});

	const isCurrentMonth = isCurrentMonthUtc(month);
	const canGoNext = canStepForwardFrom(month);
	const now = new Date(useNow());

	return (
		<>
			<AdminLlmUsagePage
				month={month}
				now={now}
				isCurrentMonth={isCurrentMonth}
				canGoNext={canGoNext}
				workspaceSlug={workspaceSlug}
				report={report}
				isLoading={isLoading}
				error={error}
				onRetry={() => void refetch()}
				onEditOwnProviderCap={() => editOwnProviderCap(true)}
			/>
			<SetOwnProviderBudgetDialog
				open={isEditingOwnProviderCap}
				currentCapUsd={report?.ownProviderMonthlyBudgetUsd ?? null}
				fx={report?.fx}
				isCurrentMonth={isCurrentMonth}
				isPending={updateOwnProviderCap.isPending}
				serverError={
					updateOwnProviderCap.error == null
						? null
						: problemDetailOf(updateOwnProviderCap.error, "Couldn't save the cap")
				}
				onOpenChange={(open) => {
					if (!open) {
						editOwnProviderCap(false);
						updateOwnProviderCap.reset();
					}
				}}
				onSubmit={(monthlyBudgetUsd) =>
					updateOwnProviderCap.mutate({
						path: { workspaceSlug },
						body: { monthlyBudgetUsd: monthlyBudgetUsd ?? undefined },
					})
				}
			/>
		</>
	);
}
