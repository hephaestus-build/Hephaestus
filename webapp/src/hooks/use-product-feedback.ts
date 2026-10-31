import { useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "@/lib/toast";

import {
	submitInstanceProductFeedbackMutation,
	submitWorkspaceProductFeedbackMutation,
} from "@/api/@tanstack/react-query.gen";
import type { FeedbackRequest } from "@/api/types.gen";
import { READERS } from "@/components/product-feedback/feedback-copy";
import { problemDetailOf, problemStatusOf } from "@/lib/problem-detail";
import { hasText } from "@/lib/text";

const FEEDBACK_SEND = ["product-feedback-send"];

const DRAFT_KEPT = "Your draft is still here.";

function submissionError(error: unknown): string {
	const status = problemStatusOf(error);
	if (status === undefined) {
		return `We could not send that. ${DRAFT_KEPT} Check your connection, then try again.`;
	}
	if (status === 429) {
		return `Wait a minute before sending more feedback. ${DRAFT_KEPT}`;
	}
	if (status === 401) {
		return "Your session has expired. Sign in again before sending.";
	}
	return `We could not send that. ${problemDetailOf(error, "The server refused the request.")} ${DRAFT_KEPT}`;
}

const FEEDBACK_SENT: Record<FeedbackRequest["kind"], string> = {
	IDEA: `Thanks. Your idea is on its way to ${READERS}.`,
	BUG: `Thanks. Your bug report is on its way to ${READERS}.`,
	FEEDBACK: `Thanks. Your feedback is on its way to ${READERS}.`,
};

export function useSubmitProductFeedback(workspaceSlug: string | undefined) {
	const queryClient = useQueryClient();
	const shared = {
		mutationKey: FEEDBACK_SEND,
		retry: false,
		onSuccess: (_: unknown, variables: { body: FeedbackRequest }) =>
			toast.success(FEEDBACK_SENT[variables.body.kind]),
	};
	const workspaceMutation = useMutation({ ...submitWorkspaceProductFeedbackMutation(), ...shared });
	const instanceMutation = useMutation({ ...submitInstanceProductFeedbackMutation(), ...shared });
	const mutation = hasText(workspaceSlug) ? workspaceMutation : instanceMutation;
	return {
		isPending: mutation.isPending,
		error: mutation.isError ? submissionError(mutation.error) : undefined,
		reset: mutation.reset,
		submit: async (body: FeedbackRequest) => {
			if (queryClient.isMutating({ mutationKey: FEEDBACK_SEND }) > 0) {
				return false;
			}
			try {
				await (hasText(workspaceSlug)
					? workspaceMutation.mutateAsync({ path: { workspaceSlug }, body })
					: instanceMutation.mutateAsync({ body }));
				return true;
			} catch {
				return false;
			}
		},
	};
}
