import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import {
	getProductFeedbackSharingOptions,
	getProductFeedbackSharingQueryKey,
	submitInstanceProductFeedbackMutation,
	submitWorkspaceProductFeedbackMutation,
} from "@/api/@tanstack/react-query.gen";
import type { FeedbackRequest } from "@/api/types.gen";
import type { ProductFeedbackSharingChoice } from "@/components/product-feedback/ProductFeedbackDialog";
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
	if (status === 422) {
		return `Choose whether this can become a public GitHub issue, then send it again. ${DRAFT_KEPT}`;
	}
	return `We could not send that. ${problemDetailOf(error, "The server refused the request.")} ${DRAFT_KEPT}`;
}

export function useSubmitProductFeedback(workspaceSlug: string | undefined) {
	const queryClient = useQueryClient();
	const shared = {
		mutationKey: FEEDBACK_SEND,
		retry: false,
		// A first answer to the sharing question is the person's choice from now on; a 422 means the
		// composer asked too little, so it reads the choice again.
		onSettled: (_data: unknown, error: unknown, variables: { body: FeedbackRequest }) => {
			if (variables.body.share !== undefined || problemStatusOf(error) === 422) {
				void queryClient.invalidateQueries({ queryKey: getProductFeedbackSharingQueryKey() });
			}
		},
	};
	const workspaceMutation = useMutation({ ...submitWorkspaceProductFeedbackMutation(), ...shared });
	const instanceMutation = useMutation({ ...submitInstanceProductFeedbackMutation(), ...shared });
	const mutation = hasText(workspaceSlug) ? workspaceMutation : instanceMutation;
	return {
		isPending: mutation.isPending,
		error: mutation.isError ? submissionError(mutation.error) : undefined,
		/** The answer to the accepted Send, until `reset`. */
		receipt: mutation.data,
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

/** The composer's question about public issues, read while the composer is open. */
export function useProductFeedbackSharing(enabled: boolean) {
	const query = useQuery({ ...getProductFeedbackSharingOptions(), enabled });
	let sharing: ProductFeedbackSharingChoice;
	if (query.isPending) {
		sharing = enabled ? { status: "loading" } : { status: "off" };
	} else if (query.isError) {
		// An unread choice asks nothing: the server answers 422 when it needs one, and the composer
		// then reads the choice again.
		sharing = { status: "off" };
	} else if (!hasText(query.data.publicRepository)) {
		sharing = query.data.filesToGitHub ? { status: "private" } : { status: "off" };
	} else if (query.data.allowPublic === undefined) {
		sharing = { status: "ask" };
	} else {
		sharing = { status: "remembered", allowPublic: query.data.allowPublic };
	}
	return { sharing, publicRepository: query.data?.publicRepository };
}
