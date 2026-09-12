import { useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import {
	adminDeleteProductSurveyMutation,
	adminGetProductSurveyQueryKey,
	adminGetProductSurveySummaryQueryKey,
	adminListProductSurveyResponsesQueryKey,
	adminListProductSurveysQueryKey,
	adminUpdateProductSurveyMutation,
} from "@/api/@tanstack/react-query.gen";
import type { Survey, SurveyEdit } from "@/api/types.gen";
import { filedUnder, pathString, usePendingMutationIds } from "@/hooks/use-pending-mutation-ids";
import { productSurveyQueryScope } from "@/hooks/use-product-feedback";
import { problemDetailOf } from "@/lib/problem-detail";

/** One key for every write to a survey, so a row waits for whichever change is in flight. */
const SURVEY_WRITE_KEY = ["adminWriteProductSurvey"];

function editOf(survey: Survey): SurveyEdit {
	return {
		title: survey.title,
		description: survey.description,
		startsAt: survey.startsAt,
		endsAt: survey.endsAt,
		active: survey.active,
	};
}

/**
 * What the table and the results drawer can do to a published survey. Every success refreshes the
 * list and the signed-in administrator's own invitations, which are read from the same rows.
 */
export function useSurveyLifecycle() {
	const queryClient = useQueryClient();
	const invalidateSurveys = () => {
		void queryClient.invalidateQueries({ queryKey: adminListProductSurveysQueryKey() });
		void queryClient.invalidateQueries({ queryKey: productSurveyQueryScope() });
	};
	const update = useMutation({
		...filedUnder(SURVEY_WRITE_KEY, adminUpdateProductSurveyMutation()),
		onSuccess: (updated) => {
			queryClient.setQueryData(
				adminGetProductSurveyQueryKey({ path: { surveyId: updated.id } }),
				updated,
			);
			invalidateSurveys();
		},
		onError: (error) =>
			toast.error("Couldn't change the survey", { description: problemDetailOf(error) }),
	});
	const remove = useMutation({
		...filedUnder(SURVEY_WRITE_KEY, adminDeleteProductSurveyMutation()),
		onSuccess: (_data, variables) => {
			const path = { path: { surveyId: variables.path.surveyId } };
			queryClient.removeQueries({ queryKey: adminGetProductSurveyQueryKey(path) });
			queryClient.removeQueries({ queryKey: adminGetProductSurveySummaryQueryKey(path) });
			queryClient.removeQueries({ queryKey: adminListProductSurveyResponsesQueryKey(path) });
			invalidateSurveys();
			toast.success("Survey deleted.");
		},
		onError: (error) =>
			toast.error("Couldn't delete the survey", { description: problemDetailOf(error) }),
	});
	const pendingIds = usePendingMutationIds(SURVEY_WRITE_KEY, (variables) =>
		pathString(variables, "surveyId"),
	);

	return {
		pendingIds,
		toggleActive: (survey: Survey, active: boolean) =>
			update.mutate(
				{ path: { surveyId: survey.id }, body: { ...editOf(survey), active } },
				{ onSuccess: () => toast.success(active ? "Survey resumed." : "Survey paused.") },
			),
		end: (survey: Survey) =>
			update.mutate(
				{ path: { surveyId: survey.id }, body: { ...editOf(survey), endsAt: new Date() } },
				{ onSuccess: () => toast.success("Survey ended.") },
			),
		remove: (survey: Survey) => remove.mutate({ path: { surveyId: survey.id } }),
	};
}
