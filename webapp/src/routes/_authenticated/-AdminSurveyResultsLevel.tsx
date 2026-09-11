import { keepPreviousData, useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { toast } from "sonner";

import {
	adminGetProductSurveyOptions,
	adminGetProductSurveySummaryOptions,
	adminListProductSurveyResponsesOptions,
} from "@/api/@tanstack/react-query.gen";
import { adminExportProductSurveyResponses } from "@/api/sdk.gen";
import type { Survey } from "@/api/types.gen";
import {
	AdminSurveyResults,
	type AdminSurveyResultsState,
} from "@/components/admin/feedback/AdminSurveyResults";
import { saveTextFile } from "@/lib/download";

const RESPONSES_PAGE_SIZE = 20;

export interface AdminSurveyResultsLevelProps {
	surveyId: string;
	now: number;
	nested?: boolean;
	pending: boolean;
	onToggleActive: (survey: Survey, active: boolean) => void;
	onEnd: (survey: Survey) => void;
	/** Deleting from inside the drawer closes it first, so the level never renders a survey that is gone. */
	onDelete: (survey: Survey) => void;
}

export function AdminSurveyResultsLevel({
	surveyId,
	now,
	nested,
	pending,
	onToggleActive,
	onEnd,
	onDelete,
}: AdminSurveyResultsLevelProps) {
	const [page, setPage] = useState(0);
	const path = { surveyId };
	const surveyQuery = useQuery(adminGetProductSurveyOptions({ path }));
	const summaryQuery = useQuery(adminGetProductSurveySummaryOptions({ path }));
	const responsesQuery = useQuery({
		...adminListProductSurveyResponsesOptions({
			path,
			query: { page, size: RESPONSES_PAGE_SIZE },
		}),
		placeholderData: keepPreviousData,
	});
	const exportResponses = useMutation({
		mutationFn: async () => {
			const { data, error } = await adminExportProductSurveyResponses({ path });
			if (error || typeof data !== "string") throw new Error("Export failed");
			saveTextFile(data, `survey-${surveyId}-responses.csv`, "text/csv;charset=utf-8;");
		},
		onError: () => toast.error("Couldn't export the responses. Please try again."),
	});

	const state: AdminSurveyResultsState =
		surveyQuery.isPending || summaryQuery.isPending || responsesQuery.isPending
			? { status: "loading" }
			: surveyQuery.isError || summaryQuery.isError || responsesQuery.isError
				? {
						status: "error",
						error: surveyQuery.error ?? summaryQuery.error ?? responsesQuery.error,
						onRetry: () => {
							void surveyQuery.refetch();
							void summaryQuery.refetch();
							void responsesQuery.refetch();
						},
					}
				: {
						status: "ready",
						survey: surveyQuery.data,
						summary: summaryQuery.data,
						responses: responsesQuery.data.content ?? [],
						page,
						totalPages: responsesQuery.data.page?.totalPages ?? 0,
						onPageChange: setPage,
					};

	return (
		<AdminSurveyResults
			state={state}
			now={now}
			nested={nested}
			exporting={exportResponses.isPending}
			onExport={() => exportResponses.mutate()}
			pending={pending}
			onToggleActive={onToggleActive}
			onEnd={onEnd}
			onDelete={onDelete}
		/>
	);
}
