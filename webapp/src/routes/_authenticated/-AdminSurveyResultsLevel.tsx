import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { toast } from "sonner";

import {
	adminGetProductSurveyOptions,
	adminPreviewSurveyEmailInvitationsOptions,
	adminPreviewSurveyEmailInvitationsQueryKey,
	adminSendSurveyEmailInvitationsMutation,
	adminGetProductSurveySummaryOptions,
	adminListProductSurveyResponsesOptions,
} from "@/api/@tanstack/react-query.gen";
import { adminExportProductSurveyResponses } from "@/api/sdk.gen";
import type { Survey } from "@/api/types.gen";
import {
	AdminSurveyEmailInvitations,
	type SurveyEmailInvitationsState,
} from "@/components/admin/product-feedback/AdminSurveyEmailInvitations";
import {
	AdminSurveyResults,
	type AdminSurveyResultsState,
} from "@/components/admin/product-feedback/AdminSurveyResults";
import { saveTextFile } from "@/lib/download";

const RESPONSES_PAGE_SIZE = 20;

export interface AdminSurveyResultsLevelProps {
	surveyId: string;
	now: number;
	nested?: boolean;
	pending: boolean;
	onToggleActive: (survey: Survey, active: boolean) => void;
	onEnd: (survey: Survey) => void;
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
	const queryClient = useQueryClient();
	const path = { surveyId };
	const emailQuery = useQuery(adminPreviewSurveyEmailInvitationsOptions({ path }));
	const queueEmails = useMutation({
		...adminSendSurveyEmailInvitationsMutation(),
		onSuccess: async (summary) => {
			queryClient.setQueryData(adminPreviewSurveyEmailInvitationsQueryKey({ path }), summary);
			await queryClient.invalidateQueries({
				queryKey: adminPreviewSurveyEmailInvitationsQueryKey({ path }),
			});
			toast.success(
				`${summary.queued} invitations queued in this batch. Relay acceptance appears separately.`,
			);
		},
		onError: async () => {
			await queryClient.invalidateQueries({
				queryKey: adminPreviewSurveyEmailInvitationsQueryKey({ path }),
			});
			toast.error("Could not confirm the invitation request. Refresh counts before trying again.");
		},
	});
	let emailState: SurveyEmailInvitationsState;
	if (emailQuery.isError) {
		emailState = {
			status: "error",
			error: emailQuery.error,
			onRetry: () => {
				void emailQuery.refetch();
			},
		};
	} else if (emailQuery.data === undefined) {
		emailState = { status: "loading" };
	} else {
		emailState = {
			status: "ready",
			summary: emailQuery.data,
			isPending: queueEmails.isPending,
			onRefresh: () => {
				void emailQuery.refetch();
			},
			onQueue: (sendReminder) => {
				if (!queueEmails.isPending) {
					queueEmails.mutate({ path, body: { sendReminder } });
				}
			},
		};
	}
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
			if (error !== undefined || typeof data !== "string") {
				throw new Error("Export failed");
			}
			saveTextFile(data, `survey-${surveyId}-responses.csv`, "text/csv;charset=utf-8;");
		},
		onError: () => toast.error("Couldn't export the responses. Please try again."),
	});

	let state: AdminSurveyResultsState;
	if (surveyQuery.isPending || summaryQuery.isPending || responsesQuery.isPending) {
		state = { status: "loading" };
	} else if (surveyQuery.isError || summaryQuery.isError || responsesQuery.isError) {
		state = {
			status: "error",
			error: surveyQuery.error ?? summaryQuery.error ?? responsesQuery.error,
			onRetry: () => {
				void surveyQuery.refetch();
				void summaryQuery.refetch();
				void responsesQuery.refetch();
			},
		};
	} else {
		state = {
			status: "ready",
			survey: surveyQuery.data,
			summary: summaryQuery.data,
			responses: responsesQuery.data.content ?? [],
			page,
			totalPages: responsesQuery.data.page?.totalPages ?? 0,
			onPageChange: setPage,
		};
	}

	return (
		<AdminSurveyResults
			emailInvitations={<AdminSurveyEmailInvitations state={emailState} />}
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
