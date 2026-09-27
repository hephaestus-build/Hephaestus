import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { toast } from "sonner";
import {
	decideFeedbackProposalMutation,
	getPracticeReviewFeedbackOptions,
	getPracticeReviewFeedbackQueryKey,
	listPracticeReviewFeedbackQueryKey,
	listPracticesOptions,
	updatePracticeReviewFeedbackWithdrawalMutation,
} from "@/api/@tanstack/react-query.gen";
import {
	FeedbackDetailPage,
	type FeedbackDetailPageProps,
} from "@/components/admin/practice-reviews/FeedbackDetailPage";
import {
	type ProposalRejectionReason,
	ProposalReviewPage,
} from "@/components/admin/practice-reviews/ProposalReviewPage";
import { isRecord } from "@/lib/is-record";
import { workspaceAdminHead } from "@/lib/page-title";
import { problemDetailOf } from "@/lib/problem-detail";
import { queryOperationId } from "@/lib/query-operation-id";

export const Route = createFileRoute(
	"/_authenticated/w/$workspaceSlug/admin/practices/reviews/delivery/$feedbackId",
)({
	head: workspaceAdminHead("Feedback details"),
	component: FeedbackDetailRoute,
});

/** Every read in this workspace that shows a practice-page card or quotes it, so none keeps a withdrawn one. */
const READS_OF_FEEDBACK_WITHDRAWAL: ReadonlySet<string> = new Set([
	"listPracticeReviewFeedback",
	"getPracticeProfileOverview",
	"getInAppFeedback",
]);

function FeedbackDetailRoute() {
	const { workspaceSlug, feedbackId } = Route.useParams();
	const search = Route.useSearch();
	const queryClient = useQueryClient();
	const detailKey = getPracticeReviewFeedbackQueryKey({ path: { workspaceSlug, feedbackId } });

	const feedbackQueryResult = useQuery({
		...getPracticeReviewFeedbackOptions({ path: { workspaceSlug, feedbackId } }),
		refetchInterval: (query) => {
			const feedback = query.state.data;
			return feedback?.deliveryState === "PREPARED" ||
				(feedback?.deliveryState === "PARTIALLY_DELIVERED" && !feedback.suppressionReason)
				? 2000
				: false;
		},
	});
	const practicesQuery = useQuery({ ...listPracticesOptions({ path: { workspaceSlug } }) });
	const decision = useMutation({
		...decideFeedbackProposalMutation(),
		onSettled: () => {
			void queryClient.invalidateQueries({ queryKey: detailKey });
			void queryClient.invalidateQueries({
				queryKey: listPracticeReviewFeedbackQueryKey({ path: { workspaceSlug } }),
			});
		},
		onSuccess: (_, variables) => {
			toast.success(
				variables.body.decision === "APPROVED"
					? "Review approved. Delivery is being checked."
					: "Review rejected",
			);
		},
		onError: (error) => {
			toast.error("Couldn't decide this review", { description: problemDetailOf(error) });
		},
	});

	const withdrawal = useMutation({
		...updatePracticeReviewFeedbackWithdrawalMutation(),
		onSuccess: (updated, variables) => {
			queryClient.setQueryData(detailKey, updated);
			void queryClient.invalidateQueries({
				predicate: ({ queryKey }) => {
					const id = queryOperationId(queryKey);
					const [key] = queryKey;
					return (
						id !== undefined &&
						READS_OF_FEEDBACK_WITHDRAWAL.has(id) &&
						isRecord(key) &&
						isRecord(key.path) &&
						key.path.workspaceSlug === workspaceSlug
					);
				},
			});
			toast.success(variables.body.withdrawn ? "Feedback withdrawn" : "Feedback restored");
		},
		onError: (error) => {
			void feedbackQueryResult.refetch();
			toast.error("Couldn't change this feedback", { description: problemDetailOf(error) });
		},
	});

	const feedback = feedbackQueryResult.data;
	if (feedback?.deliveryState === "AWAITING_APPROVAL") {
		return (
			<ProposalReviewPage
				workspaceSlug={workspaceSlug}
				feedback={feedback}
				practices={practicesQuery.data}
				isDeciding={decision.isPending}
				onApprove={(id) =>
					decision.mutate({
						path: { workspaceSlug, feedbackId: id },
						body: { decision: "APPROVED" },
					})
				}
				onReject={(id, rejectionReason?: ProposalRejectionReason, rejectionNote?: string) =>
					decision.mutate({
						path: { workspaceSlug, feedbackId: id },
						body: { decision: "REJECTED", rejectionReason, rejectionNote },
					})
				}
			/>
		);
	}

	let state: FeedbackDetailPageProps["state"];
	if (feedbackQueryResult.isPending) {
		state = { status: "loading" };
	} else if (feedbackQueryResult.isError || !feedback) {
		state = {
			status: "error",
			error: feedbackQueryResult.error,
			onRetry: () => {
				void feedbackQueryResult.refetch();
			},
		};
	} else {
		state = { status: "ready", feedback };
	}

	return (
		<FeedbackDetailPage
			workspaceSlug={workspaceSlug}
			search={search}
			state={state}
			practices={practicesQuery.data}
			isChangingWithdrawal={withdrawal.isPending}
			onChangeWithdrawal={async (withdrawn, reason) =>
				withdrawal.mutateAsync({ path: { workspaceSlug, feedbackId }, body: { withdrawn, reason } })
			}
		/>
	);
}
