import { keepPreviousData, useQuery } from "@tanstack/react-query";
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

import type { useSurveyLifecycle } from "./-admin-survey-lifecycle";

const RESPONSES_PAGE_SIZE = 20;

/** Fetches the CSV and hands it to the browser as a download; false when nothing was saved. */
async function downloadResponses(surveyId: string): Promise<boolean> {
	try {
		const { data, error } = await adminExportProductSurveyResponses({ path: { surveyId } });
		if (error || typeof data !== "string") return false;
		const url = URL.createObjectURL(new Blob([data], { type: "text/csv;charset=utf-8;" }));
		const anchor = document.createElement("a");
		anchor.href = url;
		anchor.download = `survey-${surveyId}-responses.csv`;
		document.body.appendChild(anchor);
		anchor.click();
		anchor.remove();
		URL.revokeObjectURL(url);
		return true;
	} catch {
		return false;
	}
}

export interface AdminSurveyResultsLevelProps {
	surveyId: string;
	now: number;
	nested?: boolean;
	lifecycle: ReturnType<typeof useSurveyLifecycle>;
	/** Deleting from inside the drawer closes it first, so the level never renders a survey that is gone. */
	onDelete: (survey: Survey) => void;
}

export function AdminSurveyResultsLevel({
	surveyId,
	now,
	nested,
	lifecycle,
	onDelete,
}: AdminSurveyResultsLevelProps) {
	const [page, setPage] = useState(0);
	const [exporting, setExporting] = useState(false);
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

	const handleExport = async () => {
		setExporting(true);
		const saved = await downloadResponses(surveyId);
		if (!saved) toast.error("Couldn't export the responses. Please try again.");
		setExporting(false);
	};

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
						exporting,
						onExport: () => void handleExport(),
						pending: lifecycle.pendingIds.has(surveyId),
						onToggleActive: lifecycle.toggleActive,
						onEnd: lifecycle.end,
						onDelete,
					};

	return <AdminSurveyResults state={state} now={now} nested={nested} />;
}
