import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { ClipboardList, Plus } from "lucide-react";

import { adminListProductSurveysOptions } from "@/api/@tanstack/react-query.gen";
import {
	adminSurveysSearchSchema,
	GUARDED_SURVEY_LEVEL_KINDS,
	SURVEY_LEVEL_KINDS,
	surveyLevel,
} from "@/components/admin/feedback/admin-surveys-search";
import {
	AdminSurveysTable,
	type AdminSurveysTableState,
} from "@/components/admin/feedback/AdminSurveysTable";
import { useNow } from "@/components/common/use-now";
import { encodeDetailStack, parseDetailStack } from "@/components/core/detail-drawer/detail-stack";
import { DetailDrawerStack } from "@/components/core/detail-drawer/DetailDrawerStack";
import { DetailStackLink } from "@/components/core/detail-drawer/DetailStackLink";
import { useDetailStack } from "@/components/core/detail-drawer/use-detail-stack";
import { PageHeader } from "@/components/core/PageHeader";
import { PageLayout } from "@/components/core/PageLayout";
import { buttonVariants } from "@/components/ui/button";
import { instanceAdminHead } from "@/lib/page-title";
import { pageParam, useSearchState } from "@/lib/search-params";

import { useSurveyLifecycle } from "./-admin-survey-lifecycle";
import { AdminSurveyCreateLevel } from "./-AdminSurveyCreateLevel";
import { AdminSurveyResultsLevel } from "./-AdminSurveyResultsLevel";

const PAGE_SIZE = 20;

export const Route = createFileRoute("/_authenticated/admin/surveys")({
	head: instanceAdminHead("Surveys"),
	validateSearch: adminSurveysSearchSchema,
	component: AdminSurveysPage,
});

function AdminSurveysPage() {
	const { detail, page = 0 } = Route.useSearch();
	const setSearch = useSearchState();
	const navigate = useNavigate({ from: Route.fullPath });
	const now = useNow();
	const detailStack = parseDetailStack(detail, SURVEY_LEVEL_KINDS);
	const stackControls = useDetailStack(detailStack);
	const lifecycle = useSurveyLifecycle();
	const surveysQuery = useQuery({
		...adminListProductSurveysOptions({ query: { page, size: PAGE_SIZE } }),
		placeholderData: keepPreviousData,
	});

	const state: AdminSurveysTableState = surveysQuery.isPending
		? { status: "loading" }
		: surveysQuery.isError
			? { status: "error", error: surveysQuery.error, onRetry: () => void surveysQuery.refetch() }
			: {
					status: "ready",
					surveys: surveysQuery.data.content ?? [],
					page,
					totalPages: surveysQuery.data.page?.totalPages ?? 0,
					onPageChange: (next) =>
						void setSearch((previous) => ({ ...previous, page: pageParam(next) })),
				};

	return (
		<PageLayout>
			<PageHeader
				icon={<ClipboardList />}
				title="Surveys"
				description="Ask members a few questions when it suits them; invitations appear in the app header and never interrupt."
				actions={
					<DetailStackLink entry={surveyLevel()} className={buttonVariants()}>
						<Plus className="mr-1.5 size-4" aria-hidden />
						Create survey
					</DetailStackLink>
				}
			/>

			<AdminSurveysTable
				state={state}
				now={now}
				pendingIds={lifecycle.pendingIds}
				onToggleActive={lifecycle.toggleActive}
				onEnd={lifecycle.end}
				onDelete={lifecycle.remove}
			/>

			<DetailDrawerStack
				stack={detailStack}
				guardedKinds={GUARDED_SURVEY_LEVEL_KINDS}
				onClose={stackControls.close}
			>
				{(entry, level) => {
					const done = () => stackControls.close(level.depth);
					if (entry.kind === "survey-new") {
						return (
							<AdminSurveyCreateLevel
								nested={level.nested}
								// Replaces the composer in history: Back from the results should not reopen an
								// empty composer for a survey that was just published.
								onPublished={(surveyId) =>
									void navigate({
										search: (previous) => ({
											...previous,
											detail: encodeDetailStack([surveyLevel(surveyId)]),
										}),
										replace: true,
										resetScroll: false,
									})
								}
							/>
						);
					}
					return (
						<AdminSurveyResultsLevel
							surveyId={entry.id}
							now={now}
							nested={level.nested}
							lifecycle={lifecycle}
							onDelete={(survey) => {
								done();
								lifecycle.remove(survey);
							}}
						/>
					);
				}}
			</DetailDrawerStack>
		</PageLayout>
	);
}
