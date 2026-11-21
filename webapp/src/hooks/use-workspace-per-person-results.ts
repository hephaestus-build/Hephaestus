import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import {
	getPerPersonResultsOptions,
	getPerPersonResultsQueryKey,
	listWorkspacesQueryKey,
	updatePerPersonResultsMutation,
} from "@/api/@tanstack/react-query.gen";
import type { PerPersonResults } from "@/api/types.gen";
import type { WorkspacePerPersonResultsSettingsProps } from "@/components/admin/settings/WorkspacePerPersonResultsSettings";
import { problemDetailOf } from "@/lib/problem-detail";
import { toast } from "@/lib/toast";
import { invalidateWorkspaceReads } from "@/runtime/tanstack-query/invalidate-workspace-reads";

/** The practice review reads that name people, or leave them out, by this setting. */
const READS_THAT_NAME_PEOPLE = new Set([
	"listReviews",
	"getReview",
	"getReviewQueue",
	"getReviewLineage",
	"getItemTrace",
	"listReviewResults",
	"getReviewResult",
	"listReviewProblems",
	"getReviewProblem",
	"listReviewFeedback",
	"getReviewFeedback",
	"countReviewFeedbackByLocation",
	"countReviewFeedbackByReason",
]);

/** The workspace's per-person results setting, and the change that every practice review read depends on. */
export function useWorkspacePerPersonResults(
	workspaceSlug: string,
): Pick<WorkspacePerPersonResultsSettingsProps, "state" | "onChange"> {
	const queryClient = useQueryClient();
	const options = { path: { workspaceSlug } };
	const query = useQuery(getPerPersonResultsOptions(options));
	const mutation = useMutation({
		...updatePerPersonResultsMutation(),
		onSuccess: (data: PerPersonResults) => {
			queryClient.setQueryData(getPerPersonResultsQueryKey(options), data);
			toast.success(
				data.enabled
					? "Admins now see practice results per person"
					: "Admins now see practice results with no names",
			);
		},
		onError: (error) => {
			toast.error("We could not change this setting", { description: problemDetailOf(error) });
		},
		onSettled: async () =>
			Promise.all([
				invalidateWorkspaceReads(queryClient, workspaceSlug, READS_THAT_NAME_PEOPLE),
				// The pages that show or hide names read the setting from the workspace list.
				queryClient.invalidateQueries({ queryKey: listWorkspacesQueryKey() }),
			]),
	});
	let state: WorkspacePerPersonResultsSettingsProps["state"];
	if (query.isPending) {
		state = { status: "loading" };
	} else if (query.isError) {
		state = {
			status: "error",
			error: query.error,
			onRetry: () => {
				void query.refetch();
			},
		};
	} else {
		state = {
			status: "ready",
			enabled: query.data.enabled,
			canTurnOn: query.data.canTurnOn,
			pending: mutation.isPending,
		};
	}
	return { state, onChange: (enabled) => mutation.mutate({ ...options, body: { enabled } }) };
}
