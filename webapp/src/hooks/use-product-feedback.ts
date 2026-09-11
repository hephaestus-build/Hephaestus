import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import {
	acknowledgeProductSurveyInvitationMutation,
	dismissProductSurveyMutation,
	listProductSurveyInvitationsOptions,
	listProductSurveyInvitationsQueryKey,
	restoreProductSurveyMutation,
	submitInstanceProductFeedbackMutation,
	submitProductSurveyResponseMutation,
	submitWorkspaceProductFeedbackMutation,
} from "@/api/@tanstack/react-query.gen";
import type { Answer, FeedbackRequest } from "@/api/types.gen";
import { problemDetailOf, problemStatusOf } from "@/lib/problem-detail";

/**
 * The invitation cache across every workspace. An instance-wide survey is handled once per
 * account, so a decision made in one workspace has to leave every workspace's list.
 */
export function productSurveyQueryScope() {
	const [{ path: _path, ...scope }] = listProductSurveyInvitationsQueryKey({
		path: { workspaceSlug: "" },
	});
	return [scope];
}

/**
 * One key per decision kind, so a second submit can be refused while the first is still in flight:
 * `isPending` reaches React on a later macrotask, `queryClient.isMutating` answers at once.
 */
const SURVEY_DECISION = ["product-survey-decision"];
const FEEDBACK_SEND = ["product-feedback-send"];

const DRAFT_KEPT = "Your draft is still here.";

function submissionError(error: unknown): string {
	const status = problemStatusOf(error);
	if (status === undefined)
		return `Couldn't send. ${DRAFT_KEPT} Check your connection and try again.`;
	if (status === 429) return `Please wait a minute before sending more feedback. ${DRAFT_KEPT}`;
	if (status === 401) return "Your session has expired. Sign in again before sending.";
	if (status === 409)
		return "This survey was already answered or declined, possibly in another tab.";
	if (status === 404) return "This survey is no longer available. Your answers have not been sent.";
	return `Couldn't send (${problemDetailOf(error, "the server refused the request")}). ${DRAFT_KEPT}`;
}

export function useProductSurveys(workspaceSlug: string | undefined) {
	const queryClient = useQueryClient();
	const slug = workspaceSlug ?? "";
	const query = useQuery({
		...listProductSurveyInvitationsOptions({ path: { workspaceSlug: slug } }),
		enabled: !!workspaceSlug,
	});
	const removeFromCaches = (id: string) => {
		queryClient.setQueriesData({ queryKey: productSurveyQueryScope() }, (data: typeof query.data) =>
			data?.filter((survey) => survey.id !== id),
		);
		void queryClient.invalidateQueries({ queryKey: productSurveyQueryScope() });
	};
	// Seen is bookkeeping, not a decision: a failure is invisible and the next visit reports it again.
	const acknowledge = useMutation({
		...acknowledgeProductSurveyInvitationMutation(),
		retry: false,
		onSuccess: (_, variables) => {
			queryClient.setQueriesData(
				{ queryKey: productSurveyQueryScope() },
				(data: typeof query.data) =>
					data?.map((survey) =>
						survey.id === variables.path.surveyId ? { ...survey, seen: true } : survey,
					),
			);
		},
	});
	const submit = useMutation({
		...submitProductSurveyResponseMutation(),
		mutationKey: SURVEY_DECISION,
		retry: false,
		onSuccess: (_, variables) => {
			removeFromCaches(variables.path.surveyId);
			toast.success("Thank you — your response was sent to this instance's administrators.");
		},
	});
	const restore = useMutation({
		...restoreProductSurveyMutation(),
		mutationKey: SURVEY_DECISION,
		retry: false,
		onSuccess: () => {
			void queryClient.invalidateQueries({ queryKey: productSurveyQueryScope() });
			toast.success("Survey restored. You can answer it from Feedback in the header.");
		},
		onError: () =>
			toast.error(
				"Couldn't undo the decline. The survey may no longer be available. Please try again.",
			),
	});
	const decline = useMutation({
		...dismissProductSurveyMutation(),
		mutationKey: SURVEY_DECISION,
		retry: false,
		onSuccess: (_, variables) => {
			removeFromCaches(variables.path.surveyId);
			toast.success("Survey declined.", {
				duration: 15000,
				action: { label: "Undo", onClick: () => restore.mutate({ path: variables.path }) },
			});
		},
	});
	const deciding = () =>
		!workspaceSlug || queryClient.isMutating({ mutationKey: SURVEY_DECISION }) > 0;
	return {
		query,
		isPending: submit.isPending || decline.isPending || restore.isPending,
		error: submit.isError
			? submissionError(submit.error)
			: decline.isError
				? submissionError(decline.error)
				: undefined,
		reset: () => {
			submit.reset();
			decline.reset();
		},
		acknowledge: (surveyId: string) => {
			if (workspaceSlug) acknowledge.mutate({ path: { workspaceSlug: slug, surveyId } });
		},
		submit: async (surveyId: string, answers: Answer[]) => {
			if (deciding()) return false;
			decline.reset();
			try {
				await submit.mutateAsync({ path: { workspaceSlug: slug, surveyId }, body: { answers } });
				return true;
			} catch {
				return false;
			}
		},
		decline: async (surveyId: string) => {
			if (deciding()) return false;
			submit.reset();
			try {
				await decline.mutateAsync({ path: { workspaceSlug: slug, surveyId } });
				return true;
			} catch {
				return false;
			}
		},
	};
}

export function useSubmitProductFeedback(workspaceSlug: string | undefined) {
	const queryClient = useQueryClient();
	const shared = {
		mutationKey: FEEDBACK_SEND,
		retry: false,
		onSuccess: () =>
			toast.success("Thanks — your feedback was sent to this instance's administrators."),
	};
	const workspaceMutation = useMutation({ ...submitWorkspaceProductFeedbackMutation(), ...shared });
	const instanceMutation = useMutation({ ...submitInstanceProductFeedbackMutation(), ...shared });
	const mutation = workspaceSlug ? workspaceMutation : instanceMutation;
	return {
		isPending: mutation.isPending,
		error: mutation.isError ? submissionError(mutation.error) : undefined,
		reset: mutation.reset,
		submit: async (body: FeedbackRequest) => {
			if (queryClient.isMutating({ mutationKey: FEEDBACK_SEND }) > 0) return false;
			try {
				if (workspaceSlug) await workspaceMutation.mutateAsync({ path: { workspaceSlug }, body });
				else await instanceMutation.mutateAsync({ body });
				return true;
			} catch {
				return false;
			}
		},
	};
}
