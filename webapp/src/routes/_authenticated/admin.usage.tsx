import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, Link, retainSearchParams } from "@tanstack/react-router";
import { CircleDollarSign } from "lucide-react";
import { useRef, useState } from "react";
import { toast } from "sonner";
import { z } from "zod";

import {
	adminGetLlmUsageReportOptions,
	adminGetLlmUsageReportQueryKey,
	adminUpdateWorkspaceLlmBudgetMutation,
	getLlmUsageReportOptions,
	getLlmUsageReportQueryKey,
} from "@/api/@tanstack/react-query.gen";
import type { AdminWorkspaceLlmUsage } from "@/api/types.gen";
import {
	type AdminInstanceUsageView,
	AdminInstanceLlmUsageTable,
	INSTANCE_USAGE_SEARCH_MAX_LENGTH,
	INSTANCE_USAGE_SORTS,
} from "@/components/admin/usage/AdminInstanceLlmUsageTable";
import { MonthNavigator } from "@/components/admin/usage/MonthNavigator";
import { SetBudgetDialog } from "@/components/admin/usage/SetBudgetDialog";
import {
	monthOf,
	USAGE_SEARCH_PARAMS,
	usageSearchSchema,
} from "@/components/admin/usage/usage-search";
import { canStepForwardFrom, isCurrentMonthUtc } from "@/components/admin/usage/usage-utils";
import { useNow } from "@/components/common/use-now";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { instanceAdminHead } from "@/lib/page-title";
import { problemDetailOf } from "@/lib/problem-detail";
import { useSearchState } from "@/lib/search-params";

/** The workspaces table's view. Each param leaves the address while it holds its default. */
const instanceUsageSearchSchema = usageSearchSchema.extend({
	q: z.string().max(INSTANCE_USAGE_SEARCH_MAX_LENGTH).optional().catch(undefined),
	sort: z.enum(INSTANCE_USAGE_SORTS).optional().catch(undefined),
	desc: z
		.union([z.boolean(), z.enum(["true", "false"]).transform((value) => value === "true")])
		.optional()
		.catch(undefined),
	page: z.coerce.number().int().min(0).optional().catch(undefined),
});

/** One array while the list loads, so the table's data keeps its identity. */
const NO_WORKSPACES: AdminWorkspaceLlmUsage[] = [];

/** Most shared-model spend first: the money an instance admin sets budgets for. */
const DEFAULT_VIEW: AdminInstanceUsageView = { q: "", sort: "sharedSpend", desc: true, page: 0 };

export const Route = createFileRoute("/_authenticated/admin/usage")({
	head: instanceAdminHead("AI usage"),
	component: AdminInstanceUsagePage,
	validateSearch: instanceUsageSearchSchema,
	search: { middlewares: [retainSearchParams(USAGE_SEARCH_PARAMS)] },
});

function AdminInstanceUsagePage() {
	const queryClient = useQueryClient();
	const search = Route.useSearch();
	const month = monthOf(search);
	const setSearch = useSearchState();
	const view: AdminInstanceUsageView = {
		q: search.q ?? DEFAULT_VIEW.q,
		sort: search.sort ?? DEFAULT_VIEW.sort,
		desc: search.desc ?? DEFAULT_VIEW.desc,
		page: search.page ?? DEFAULT_VIEW.page,
	};
	const [editing, setEditing] = useState<AdminWorkspaceLlmUsage | null>(null);
	const onScreenWorkspaceRef = useRef<AdminWorkspaceLlmUsage | null>(null);
	const editBudgetFor = (workspace: AdminWorkspaceLlmUsage | null) => {
		onScreenWorkspaceRef.current = workspace;
		setEditing(workspace);
	};
	const [expanded, setExpanded] = useState<AdminWorkspaceLlmUsage | null>(null);

	const listQuery = useQuery({
		...adminGetLlmUsageReportOptions({ query: { month } }),
		placeholderData: keepPreviousData,
	});
	const fx = listQuery.data?.fx;
	const detailQuery = useQuery({
		...getLlmUsageReportOptions({
			path: { workspaceSlug: expanded?.workspaceSlug ?? "" },
			query: { month },
		}),
		enabled: expanded != null,
	});

	const updateBudget = useMutation({
		...adminUpdateWorkspaceLlmBudgetMutation(),
		onSuccess: (_data, variables) => {
			void queryClient.invalidateQueries({ queryKey: adminGetLlmUsageReportQueryKey() });
			void queryClient.invalidateQueries({
				queryKey: getLlmUsageReportQueryKey({
					path: { workspaceSlug: variables.path.workspaceSlug },
				}),
			});
			toast.success(
				variables.body.monthlyBudgetUsd == null
					? "Budget removed. New calls resume within a minute."
					: "Budget saved. New calls resume within a minute.",
			);
			editBudgetFor(null);
		},
		onError: (error, variables) => {
			if (onScreenWorkspaceRef.current?.workspaceSlug !== variables.path.workspaceSlug) {
				toast.error("We could not save the budget", { description: problemDetailOf(error) });
			}
		},
	});

	const canGoNext = canStepForwardFrom(month);
	const isCurrentMonth = isCurrentMonthUtc(month);
	const now = new Date(useNow());

	const handleSubmitBudget = (monthlyBudgetUsd: number | null) => {
		if (!editing) {
			return;
		}
		updateBudget.mutate({
			path: { workspaceSlug: editing.workspaceSlug },
			body: { monthlyBudgetUsd: monthlyBudgetUsd ?? undefined },
		});
	};

	return (
		<PageLayout>
			<PageHeader
				icon={<CircleDollarSign />}
				title="AI usage"
				description="Review model usage and workspace budgets for this instance."
				actions={
					<MonthNavigator
						month={month}
						canGoNext={canGoNext}
						renderMonthLink={(nextMonth, props) => (
							<Link
								{...props}
								from={Route.fullPath}
								to="/admin/usage"
								search={(previous) => ({ ...previous, month: nextMonth })}
							/>
						)}
					/>
				}
			/>

			<AdminInstanceLlmUsageTable
				rows={listQuery.data?.workspaces ?? NO_WORKSPACES}
				month={month}
				now={now}
				fx={fx}
				isCurrentMonth={isCurrentMonth}
				isLoading={listQuery.isLoading}
				error={listQuery.error}
				onRetry={() => {
					void listQuery.refetch();
				}}
				view={view}
				onViewChange={(patch) => {
					void setSearch(
						(previous) => {
							const next = { ...view, ...patch };
							return {
								...previous,
								q: next.q === "" ? undefined : next.q,
								sort: next.sort === DEFAULT_VIEW.sort ? undefined : next.sort,
								desc: next.desc === DEFAULT_VIEW.desc ? undefined : next.desc,
								page: next.page === 0 ? undefined : next.page,
							};
						},
						{ replace: true },
					);
				}}
				expandedWorkspaceSlug={expanded?.workspaceSlug ?? null}
				detailReport={detailQuery.data}
				isDetailLoading={detailQuery.isLoading}
				detailError={detailQuery.error}
				onRetryDetail={() => {
					void detailQuery.refetch();
				}}
				onToggleDetails={(workspace) =>
					setExpanded((current) =>
						current?.workspaceSlug === workspace.workspaceSlug ? null : workspace,
					)
				}
				onEditSharedModelBudget={editBudgetFor}
			/>

			<SetBudgetDialog
				workspace={editing}
				fx={fx}
				isCurrentMonth={isCurrentMonth}
				isPending={updateBudget.isPending}
				serverError={
					updateBudget.error == null
						? null
						: problemDetailOf(updateBudget.error, "We could not save the budget")
				}
				onOpenChange={(open) => {
					if (!open) {
						editBudgetFor(null);
						updateBudget.reset();
					}
				}}
				onSubmit={handleSubmitBudget}
			/>
		</PageLayout>
	);
}
