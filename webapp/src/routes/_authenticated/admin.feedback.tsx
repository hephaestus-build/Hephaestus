import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { Inbox } from "lucide-react";
import { useState } from "react";
import { z } from "zod";
import { toast } from "@/lib/toast";

import {
	adminListLlmModelsOptions,
	adminListProductFeedbackOptions,
	adminListProductFeedbackQueryKey,
	adminTriageProductFeedbackMutation,
	getProductFeedbackDestinationsOptions,
	getProductFeedbackDestinationsQueryKey,
	listProductFeedbackNeedsActionOptions,
	listProductFeedbackNeedsActionQueryKey,
	retryProductFeedbackFilingMutation,
	updateProductFeedbackDestinationsMutation,
} from "@/api/@tanstack/react-query.gen";
import {
	AdminFeedbackList,
	type AdminFeedbackListState,
	type FeedbackStatusFilter,
} from "@/components/admin/product-feedback/AdminFeedbackList";
import {
	AdminProductFeedbackDestinationsCard,
	type AdminProductFeedbackDestinationsState,
} from "@/components/admin/product-feedback/AdminProductFeedbackDestinationsCard";
import {
	AdminProductFeedbackNeedsActionList,
	type AdminProductFeedbackNeedsActionState,
} from "@/components/admin/product-feedback/AdminProductFeedbackNeedsActionList";
import { ConfirmAccessDialog } from "@/components/auth/ConfirmAccessDialog";
import { FilterToggle } from "@/components/common/FilterToggle";
import { ResultCount } from "@/components/common/ResultCount";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Section } from "@/components/layout/Section";
import { useClampedPage } from "@/hooks/use-clamped-page";
import { useConfirmAccess } from "@/hooks/use-confirm-access";
import { filedUnder, pathString, usePendingMutationIds } from "@/hooks/use-pending-mutation-ids";
import { instanceAdminHead } from "@/lib/page-title";
import { problemDetailOf, type StepUpChallenge, stepUpChallengeOf } from "@/lib/problem-detail";
import { pageParam, useSearchState } from "@/lib/search-params";

const PAGE_SIZE = 20;
const TRIAGE_KEY = ["adminTriageProductFeedback"];
const RETRY_KEY = ["retryProductFeedbackFiling"];

const STATUS_OPTIONS: { value: FeedbackStatusFilter; label: string }[] = [
	{ value: "OPEN", label: "Open" },
	{ value: "RESOLVED", label: "Resolved" },
	{ value: "ALL", label: "All" },
];

export const Route = createFileRoute("/_authenticated/admin/feedback")({
	head: instanceAdminHead("Product feedback"),
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
		onSuccess: () => {
			void queryClient.invalidateQueries({ queryKey: adminListProductFeedbackQueryKey() });
		},
		onError: (error) =>
			toast.error("We could not update the feedback", { description: problemDetailOf(error) }),
	});
	const pendingIds = usePendingMutationIds(TRIAGE_KEY, (variables) =>
		pathString(variables, "feedbackId"),
	);

	const destinationsQuery = useQuery(getProductFeedbackDestinationsOptions());
	const modelsQuery = useQuery(adminListLlmModelsOptions());
	// Changing a destination asks for a recent sign-in; the refusal opens that ask instead of a toast.
	const [challenge, setChallenge] = useState<StepUpChallenge>();
	const confirmAccess = useConfirmAccess(challenge !== undefined);
	const saveDestinations = useMutation({
		...updateProductFeedbackDestinationsMutation(),
		onSuccess: (saved) => {
			queryClient.setQueryData(getProductFeedbackDestinationsQueryKey(), saved);
			toast.success("Destinations saved");
		},
		onError: (error) => {
			const stepUp = stepUpChallengeOf(error);
			if (stepUp) {
				setChallenge(stepUp);
				return;
			}
			toast.error("We could not save the destinations", { description: problemDetailOf(error) });
		},
	});
	let destinationsState: AdminProductFeedbackDestinationsState;
	if (destinationsQuery.isPending) {
		destinationsState = { status: "loading" };
	} else if (destinationsQuery.isError) {
		destinationsState = {
			status: "error",
			error: destinationsQuery.error,
			onRetry: () => {
				void destinationsQuery.refetch();
			},
		};
	} else {
		const savedModel = destinationsQuery.data.llmModelId;
		destinationsState = {
			status: "ready",
			destinations: destinationsQuery.data,
			// The saved model stays selectable after it was turned off, so a save does not drop it silently.
			models: (modelsQuery.data ?? []).filter((model) => model.enabled || model.id === savedModel),
			saving: saveDestinations.isPending,
			onSave: (body) => saveDestinations.mutate({ body }),
		};
	}

	const needsActionQuery = useQuery(listProductFeedbackNeedsActionOptions());
	const retryFiling = useMutation({
		...filedUnder(RETRY_KEY, retryProductFeedbackFilingMutation()),
		onSuccess: () => {
			toast.success("The report is filing again");
		},
		onError: (error) =>
			toast.error("We could not retry the report", { description: problemDetailOf(error) }),
		onSettled: () => {
			void queryClient.invalidateQueries({ queryKey: listProductFeedbackNeedsActionQueryKey() });
			void queryClient.invalidateQueries({ queryKey: getProductFeedbackDestinationsQueryKey() });
		},
	});
	const retryingIds = usePendingMutationIds(RETRY_KEY, (variables) =>
		pathString(variables, "feedbackId"),
	);
	let needsActionState: AdminProductFeedbackNeedsActionState;
	if (needsActionQuery.isPending) {
		needsActionState = { status: "loading" };
	} else if (needsActionQuery.isError) {
		needsActionState = {
			status: "error",
			error: needsActionQuery.error,
			onRetry: () => {
				void needsActionQuery.refetch();
			},
		};
	} else {
		needsActionState = { status: "ready", items: needsActionQuery.data.items };
	}
	const onPageChange = (next: number) => {
		void setSearch((previous) => ({ ...previous, page: pageParam(next) }));
	};
	useClampedPage(page, feedbackQuery.data?.page?.totalPages, onPageChange);

	let state: AdminFeedbackListState;
	if (feedbackQuery.isPending) {
		state = { status: "loading" };
	} else if (feedbackQuery.isError) {
		state = {
			status: "error",
			error: feedbackQuery.error,
			onRetry: () => {
				void feedbackQuery.refetch();
			},
		};
	} else {
		state = {
			status: "ready",
			items: feedbackQuery.data.content ?? [],
			filter: status,
			page,
			totalPages: feedbackQuery.data.page?.totalPages ?? 0,
			onPageChange,
		};
	}

	return (
		<PageLayout>
			<PageHeader
				icon={<Inbox />}
				title="Product feedback"
				description="Ideas, bug reports and comments from users, and where the instance files them."
			/>

			<AdminProductFeedbackDestinationsCard state={destinationsState} />

			<Section
				title="Needs action"
				description="Reports that failed to file on GitHub. The admin email already carries each one."
			>
				<AdminProductFeedbackNeedsActionList
					state={needsActionState}
					pendingIds={retryingIds}
					onRetryFiling={(item) => retryFiling.mutate({ path: { feedbackId: item.id } })}
				/>
			</Section>

			<Section
				title="Reports not on GitHub"
				description="Marking one resolved does not notify the sender."
			>
				<div className="flex flex-wrap items-center justify-between gap-3">
					<FilterToggle
						label="Show"
						options={STATUS_OPTIONS}
						value={status}
						onChange={(next) => {
							void setSearch((previous) => ({
								...previous,
								status: next === "OPEN" ? undefined : next,
								page: undefined,
							}));
						}}
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
			</Section>

			<ConfirmAccessDialog
				open={challenge !== undefined}
				onOpenChange={(open) => {
					if (!open) {
						setChallenge(undefined);
					}
				}}
				maxAgeSeconds={challenge?.maxAgeSeconds}
				providers={confirmAccess.providers}
				loading={confirmAccess.loading}
				error={confirmAccess.error}
				onRetry={confirmAccess.retry}
				onSignIn={confirmAccess.signIn}
			/>
		</PageLayout>
	);
}
