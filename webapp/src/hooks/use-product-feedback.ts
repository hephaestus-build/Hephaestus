import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import {
	acknowledgeProductSurveyInvitationMutation,
	declineProductSurveyMutation,
	listProductSurveyInvitationsOptions,
	listProductSurveyInvitationsQueryKey,
	undoProductSurveyDeclineMutation,
	submitInstanceProductFeedbackMutation,
	submitProductSurveyResponseMutation,
	submitWorkspaceProductFeedbackMutation,
} from "@/api/@tanstack/react-query.gen";
import type { Answer, FeedbackRequest, SurveyInvitation } from "@/api/types.gen";
import { studyOf } from "@/components/feedback/survey-purpose-defs";
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

function submissionError(error: unknown, subject: "survey" | "feedback"): string {
	const status = problemStatusOf(error);
	if (status === undefined)
		return `Couldn't send. ${DRAFT_KEPT} Check your connection and try again.`;
	if (status === 429) return `Please wait a minute before sending more feedback. ${DRAFT_KEPT}`;
	if (status === 401) return "Your session has expired. Sign in again before sending.";
	if (subject === "survey" && status === 409)
		return "This survey was already answered or declined, possibly in another tab.";
	if (subject === "survey" && status === 404)
		return "This survey is no longer available. Your answers have not been sent.";
	return `Couldn't send (${problemDetailOf(error, "the server refused the request")}). ${DRAFT_KEPT}`;
}

/** What a member needs to know about a survey to be thanked for it: `submit` takes it from the dialog. */
type SurveyIdentity = Pick<SurveyInvitation, "id" | "purpose" | "researchOrganization">;

function surveyAcknowledgement(survey: SurveyIdentity): string {
	return survey.purpose === "RESEARCH"
		? `Thank you — your answers were recorded for ${studyOf(survey)}.`
		: "Thank you — your answers are with this instance's administrators.";
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
	// A survey the server no longer offers (404) or has already settled (409) has to leave the menu
	// too, or the invitation reopens to the same refusal until the next refetch.
	const dropWhenGone = (error: unknown, variables: { path: { surveyId: string } }) => {
		const status = problemStatusOf(error);
		if (status === 404 || status === 409) removeFromCaches(variables.path.surveyId);
	};
	const submit = useMutation({
		...submitProductSurveyResponseMutation(),
		mutationKey: SURVEY_DECISION,
		retry: false,
		onSuccess: (_, variables) => removeFromCaches(variables.path.surveyId),
		onError: dropWhenGone,
	});
	const undoDecline = useMutation({
		...undoProductSurveyDeclineMutation(),
		mutationKey: SURVEY_DECISION,
		retry: false,
		onSuccess: () => {
			void queryClient.invalidateQueries({ queryKey: productSurveyQueryScope() });
			toast.success("Decline undone. The survey is back in the header's feedback menu.");
		},
		onError: () =>
			toast.error(
				"Couldn't undo the decline. The survey may no longer be available. Please try again.",
			),
	});
	const decline = useMutation({
		...declineProductSurveyMutation(),
		mutationKey: SURVEY_DECISION,
		retry: false,
		onSuccess: (_, variables) => {
			removeFromCaches(variables.path.surveyId);
			toast.success("Survey declined. You won't be asked again.", {
				duration: 15000,
				action: { label: "Undo", onClick: () => undoDecline.mutate({ path: variables.path }) },
			});
		},
		onError: dropWhenGone,
	});
	const deciding = () =>
		!workspaceSlug || queryClient.isMutating({ mutationKey: SURVEY_DECISION }) > 0;
	return {
		query,
		isPending: submit.isPending || decline.isPending || undoDecline.isPending,
		error: submit.isError
			? submissionError(submit.error, "survey")
			: decline.isError
				? submissionError(decline.error, "survey")
				: undefined,
		reset: () => {
			submit.reset();
			decline.reset();
		},
		acknowledge: (surveyId: string) => {
			if (workspaceSlug) acknowledge.mutate({ path: { workspaceSlug: slug, surveyId } });
		},
		submit: async (survey: SurveyIdentity, answers: Answer[]) => {
			if (deciding()) return false;
			decline.reset();
			try {
				await submit.mutateAsync({
					path: { workspaceSlug: slug, surveyId: survey.id },
					body: { answers },
				});
				toast.success(surveyAcknowledgement(survey));
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

const FEEDBACK_SENT: Record<FeedbackRequest["kind"], string> = {
	IDEA: "Thanks — your idea is with this instance's administrators.",
	BUG: "Thanks — your bug report is with this instance's administrators.",
	FEEDBACK: "Thanks — your feedback is with this instance's administrators.",
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
	const mutation = workspaceSlug ? workspaceMutation : instanceMutation;
	return {
		isPending: mutation.isPending,
		error: mutation.isError ? submissionError(mutation.error, "feedback") : undefined,
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
