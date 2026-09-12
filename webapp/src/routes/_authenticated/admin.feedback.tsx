import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { Inbox } from "lucide-react";
import { toast } from "sonner";
import { z } from "zod";

import {
	adminListProductFeedbackOptions,
	adminListProductFeedbackQueryKey,
	adminTriageProductFeedbackMutation,
} from "@/api/@tanstack/react-query.gen";
import {
	AdminFeedbackList,
	type AdminFeedbackListState,
	type FeedbackStatusFilter,
} from "@/components/admin/feedback/AdminFeedbackList";
import { FilterToggle } from "@/components/common/FilterToggle";
import { ResultCount } from "@/components/common/ResultCount";
import { PageHeader } from "@/components/core/PageHeader";
import { PageLayout } from "@/components/core/PageLayout";
import { useClampedPage } from "@/hooks/use-clamped-page";
import { filedUnder, pathString, usePendingMutationIds } from "@/hooks/use-pending-mutation-ids";
import { instanceAdminHead } from "@/lib/page-title";
import { problemDetailOf } from "@/lib/problem-detail";
import { pageParam, useSearchState } from "@/lib/search-params";

const PAGE_SIZE = 20;
const TRIAGE_KEY = ["adminTriageProductFeedback"];

const STATUS_OPTIONS: { value: FeedbackStatusFilter; label: string }[] = [
	{ value: "OPEN", label: "Open" },
	{ value: "RESOLVED", label: "Resolved" },
	{ value: "ALL", label: "All" },
];

export const Route = createFileRoute("/_authenticated/admin/feedback")({
	head: instanceAdminHead("Feedback inbox"),
	validateSearch: z.object({
		status: z.enum(["OPEN", "RESOLVED", "ALL"]).optional().catch(undefined),
		page: z.coerce.number().int().min(0).optional().catch(undefined),
	}),
	component: AdminFeedbackInboxPage,
});

function AdminFeedbackInboxPage() {
	const { status = "OPEN", page = 0 } = Route.useSearch();
	const setSearch = useSearchState();
	const queryClient = useQueryClient();
	const feedbackQuery = useQuery({
		...adminListProductFeedbackOptions({ query: { status, page, size: PAGE_SIZE } }),
		placeholderData: keepPreviousData,
	});
	const triage = useMutation({
		...filedUnder(TRIAGE_KEY, adminTriageProductFeedbackMutation()),
		// Every status, not just the one showing: an item resolved here leaves "Open" and joins "Resolved".
		onSuccess: () =>
			void queryClient.invalidateQueries({ queryKey: adminListProductFeedbackQueryKey() }),
		onError: (error) =>
			toast.error("Couldn't update the feedback", { description: problemDetailOf(error) }),
	});
	const pendingIds = usePendingMutationIds(TRIAGE_KEY, (variables) =>
		pathString(variables, "feedbackId"),
	);
	const onPageChange = (next: number) =>
		void setSearch((previous) => ({ ...previous, page: pageParam(next) }));
	useClampedPage(page, feedbackQuery.data?.page?.totalPages, onPageChange);

	const state: AdminFeedbackListState = feedbackQuery.isPending
		? { status: "loading" }
		: feedbackQuery.isError
			? {
					status: "error",
					error: feedbackQuery.error,
					onRetry: () => void feedbackQuery.refetch(),
				}
			: {
					status: "ready",
					items: feedbackQuery.data.content ?? [],
					filter: status,
					page,
					totalPages: feedbackQuery.data.page?.totalPages ?? 0,
					onPageChange,
				};

	return (
		<PageLayout>
			<PageHeader
				icon={<Inbox />}
				title="Feedback inbox"
				description="Ideas, bug reports and feedback members sent to this instance. Nobody is notified; check it regularly."
			/>

			<div className="flex flex-wrap items-center justify-between gap-3">
				<FilterToggle
					label="Show"
					options={STATUS_OPTIONS}
					value={status}
					onChange={(next) =>
						void setSearch((previous) => ({
							...previous,
							status: next === "OPEN" ? undefined : next,
							page: undefined,
						}))
					}
				/>
				<ResultCount
					total={feedbackQuery.data?.page?.totalElements}
					noun={["piece of feedback", "pieces of feedback"]}
					hasFilter={status !== "ALL"}
				/>
			</div>

			<AdminFeedbackList
				state={state}
				pendingIds={pendingIds}
				onTriage={(item, resolved) =>
					triage.mutate({ path: { feedbackId: item.id }, body: { resolved } })
				}
			/>
		</PageLayout>
	);
}
