import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRef } from "react";
import { toast } from "sonner";

import {
	dismissProductSurveyMutation,
	restoreProductSurveyMutation,
	listAvailableProductSurveysOptions,
	listAvailableProductSurveysQueryKey,
	submitInstanceProductFeedbackMutation,
	submitProductSurveyResponseMutation,
	submitWorkspaceProductFeedbackMutation,
} from "@/api/@tanstack/react-query.gen";
import type { FeedbackRequest } from "@/api/types.gen";
import { problemStatusOf } from "@/lib/problem-detail";

export function productSurveyQueryScope() {
	const [{ path: _path, ...scope }] = listAvailableProductSurveysQueryKey({
		path: { workspaceSlug: "" },
	});
	return [scope];
}

function submissionError(error: unknown): string {
	const status = problemStatusOf(error);
	if (status === 429)
		return "Please wait a minute before sending more feedback. Your draft is still here.";
	if (status === 401) return "Your session has expired. Sign in again before sending.";
	if (status === 409)
		return "This survey was already answered or declined, possibly in another tab.";
	if (status === 404) return "This survey is no longer available. Your answers have not been sent.";
	return "Couldn't send. Your draft is still here. Check your connection and try again.";
}

export function useProductSurveys(workspaceSlug: string | undefined) {
	const queryClient = useQueryClient();
	const sending = useRef(false);
	const slug = workspaceSlug ?? "";
	const query = useQuery({
		...listAvailableProductSurveysOptions({ path: { workspaceSlug: slug } }),
		enabled: !!workspaceSlug,
	});
	const removeHandledSurveyFromCaches = (id: string) => {
		queryClient.setQueriesData({ queryKey: productSurveyQueryScope() }, (data: typeof query.data) =>
			data?.filter((survey) => survey.id !== id),
		);
		void queryClient.invalidateQueries({ queryKey: productSurveyQueryScope() });
	};
	const submit = useMutation({
		...submitProductSurveyResponseMutation(),
		retry: false,
		onSettled: () => {
			sending.current = false;
		},
		onSuccess: (_, variables) => {
			removeHandledSurveyFromCaches(variables.path.surveyId);
			toast.success("Thank you — your response was sent to this instance's administrators.");
		},
	});
	const restore = useMutation({
		...restoreProductSurveyMutation(),
		retry: false,
		onSuccess: () => {
			void queryClient.invalidateQueries({ queryKey: productSurveyQueryScope() });
			toast.success("Survey restored. You can answer it from Surveys.");
		},
		onError: () =>
			toast.error(
				"Couldn't undo the decline. The survey may no longer be available. Please try again.",
			),
	});
	const dismiss = useMutation({
		...dismissProductSurveyMutation(),
		retry: false,
		onSettled: () => {
			sending.current = false;
		},
		onSuccess: (_, variables) => {
			removeHandledSurveyFromCaches(variables.path.surveyId);
			toast.success("Survey declined.", {
				duration: 15000,
				action: { label: "Undo", onClick: () => restore.mutate({ path: variables.path }) },
			});
		},
	});
	return {
		query,
		isPending: submit.isPending || dismiss.isPending || restore.isPending,
		error: submit.isError
			? submissionError(submit.error)
			: dismiss.isError
				? submissionError(dismiss.error)
				: undefined,
		reset: () => {
			submit.reset();
			dismiss.reset();
		},
		submit: async (surveyId: string, answers: Record<string, string>) => {
			if (!workspaceSlug || sending.current || restore.isPending) return false;
			sending.current = true;
			try {
				dismiss.reset();
				await submit.mutateAsync({ path: { workspaceSlug: slug, surveyId }, body: { answers } });
				return true;
			} catch {
				return false;
			}
		},
		dismiss: async (surveyId: string) => {
			if (!workspaceSlug || sending.current || restore.isPending) return false;
			sending.current = true;
			try {
				submit.reset();
				await dismiss.mutateAsync({ path: { workspaceSlug: slug, surveyId } });
				return true;
			} catch {
				return false;
			}
		},
	};
}

export function useSubmitProductFeedback(workspaceSlug: string | undefined) {
	const sending = useRef(false);
	const callbacks = {
		retry: false,
		onSettled: () => {
			sending.current = false;
		},
		onSuccess: () =>
			toast.success("Thanks — your feedback was sent to this instance's administrators."),
	};
	const workspaceMutation = useMutation({
		...submitWorkspaceProductFeedbackMutation(),
		...callbacks,
	});
	const instanceMutation = useMutation({
		...submitInstanceProductFeedbackMutation(),
		...callbacks,
	});
	const mutation = workspaceSlug ? workspaceMutation : instanceMutation;
	return {
		isPending: workspaceMutation.isPending || instanceMutation.isPending,
		error: mutation.isError ? submissionError(mutation.error) : undefined,
		submit: async (body: FeedbackRequest) => {
			if (sending.current) return false;
			sending.current = true;
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
