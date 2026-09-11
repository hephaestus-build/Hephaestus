import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, Navigate, redirect, useNavigate } from "@tanstack/react-router";
import { toast } from "sonner";
import { z } from "zod";
import {
	deleteFeedbackResponseMutation,
	getObservationOptions,
	getPracticeGroupTrendOptions,
	listGroupsOptions,
	listPracticeGroupReviewRunsInfiniteOptions,
	listPracticeGroupReviewRunsInfiniteQueryKey,
	listPracticeGroupStandingsOptions,
	listPracticeStandingsOptions,
	listReviewedPracticesOptions,
	replaceFeedbackResponseMutation,
} from "@/api/@tanstack/react-query.gen";
import type { PracticeStanding } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import {
	PracticeGroupDetailPage,
	type ReviewRunFeedState,
} from "@/components/profile/PracticeGroupDetailPage";
import {
	isEmptyFeedbackResponse,
	type ObservationDetailState,
} from "@/components/profile/review-runs";
import { useWorkspaceFeatures } from "@/hooks/use-workspace-features";
import { resolveCurrentUser } from "@/integrations/auth/guard";
import { loadedPages } from "@/integrations/tanstack-query/spring-page";
import { problemDetailOf } from "@/lib/problem-detail";
import { useSearchState } from "@/lib/search-params";

const ACTIVITY_PAGE_SIZE = 10;

const practiceGroupDetailSearchSchema = z.object({
	practice: z.string().optional(),
	observation: z.string().optional(),
});
function nextStepOf(practiceStanding?: PracticeStanding): string | undefined {
	const firstAction = practiceStanding?.toWorkOn[0];
	if (!firstAction) return undefined;
	const deliveredGuidance = firstAction.deliveredFeedback?.trim();
	const observationTitle = firstAction.title.trim();
	const distinctTitle =
		observationTitle !== practiceStanding.name.trim() ? observationTitle : undefined;
	return [deliveredGuidance, distinctTitle].find((value) => value !== undefined && value !== "");
}

export const Route = createFileRoute(
	"/_authenticated/w/$workspaceSlug/user/$username/practice-groups/$groupSlug",
)({
	validateSearch: practiceGroupDetailSearchSchema,
	remountDeps: ({ params }) => params,
	beforeLoad: async ({ context, params }) => {
		const user = await resolveCurrentUser(context.queryClient);
		const isOwnProfile = user?.username?.toLowerCase() === params.username.toLowerCase();
		if (!isOwnProfile) {
			throw redirect({
				to: "/w/$workspaceSlug/user/$username",
				params: { workspaceSlug: params.workspaceSlug, username: params.username },
				replace: true,
			});
		}
	},
	component: PracticeGroupRoute,
});

function PracticeGroupRoute() {
	const { workspaceSlug, username } = Route.useParams();
	const featureState = useWorkspaceFeatures(workspaceSlug);
	if (featureState.isError) {
		return (
			<QueryErrorAlert
				error={featureState.error}
				title="Couldn't load workspace features"
				onRetry={featureState.refetch}
			/>
		);
	}
	if (featureState.features?.practicesEnabled === false) {
		return (
			<Navigate
				to="/w/$workspaceSlug/user/$username"
				params={{ workspaceSlug, username }}
				replace
			/>
		);
	}
	if (featureState.features?.practicesEnabled !== true) {
		return <PracticeGroupDetailPage isLoading />;
	}
	return <PracticeGroupDetail />;
}

function PracticeGroupDetail() {
	const { workspaceSlug, username, groupSlug } = Route.useParams();
	const { practice: selectedPracticeSlug, observation: openObservationId } = Route.useSearch();
	const navigate = useNavigate({ from: Route.fullPath });
	const queryClient = useQueryClient();
	const setSearch = useSearchState();
	const updateSelection = (search: { practice?: string; observation?: string }) =>
		void setSearch((previous) => ({ ...previous, ...search }));

	const groupsQuery = useQuery({
		...listGroupsOptions({
			path: { workspaceSlug },
			query: { visibleInPracticeDashboardsOnly: true },
		}),
	});
	const statusesQuery = useQuery({
		...listPracticeGroupStandingsOptions({ path: { workspaceSlug } }),
	});
	const practicesQuery = useQuery({
		...listReviewedPracticesOptions({ path: { workspaceSlug } }),
	});
	const standingsQuery = useQuery({
		...listPracticeStandingsOptions({ path: { workspaceSlug } }),
	});
	const trendQuery = useQuery({
		...getPracticeGroupTrendOptions({ path: { workspaceSlug, groupSlug } }),
	});
	const reviewRunsRequest = {
		path: { workspaceSlug, groupSlug },
		query: {
			size: ACTIVITY_PAGE_SIZE,
			practiceSlug: selectedPracticeSlug,
		},
	};
	const activityQuery = useInfiniteQuery({
		...listPracticeGroupReviewRunsInfiniteOptions(reviewRunsRequest),
		initialPageParam: 0,
		getNextPageParam: (lastPage) => (lastPage.hasNext ? (lastPage.page ?? 0) + 1 : undefined),
	});
	const invalidateReviewRuns = () =>
		queryClient.invalidateQueries({
			queryKey: listPracticeGroupReviewRunsInfiniteQueryKey({ path: { workspaceSlug, groupSlug } }),
		});
	const replaceResponseMutation = useMutation({
		...replaceFeedbackResponseMutation(),
		onSuccess: invalidateReviewRuns,
		onError: (error) =>
			toast.error(problemDetailOf(error, "Could not save your feedback response")),
	});
	const deleteResponseMutation = useMutation({
		...deleteFeedbackResponseMutation(),
		onSuccess: invalidateReviewRuns,
		onError: (error) =>
			toast.error(problemDetailOf(error, "Could not withdraw your feedback response")),
	});
	const observationQuery = useQuery({
		...getObservationOptions({
			path: { workspaceSlug, observationId: openObservationId ?? "" },
		}),
		enabled: Boolean(openObservationId),
	});

	const group = groupsQuery.data?.find((candidate) => candidate.slug === groupSlug);
	const standing = statusesQuery.data?.find((candidate) => candidate.groupSlug === groupSlug);
	const standingsBySlug = new Map(
		(standingsQuery.data ?? [])
			.filter((practice) => practice.groupSlug === groupSlug)
			.map((practice) => [practice.slug, practice]),
	);
	const trendsBySlug = new Map(
		(trendQuery.data?.practices ?? []).map((practiceTrend) => [practiceTrend.slug, practiceTrend]),
	);
	const practices = practicesQuery.data
		?.filter((practice) => practice.groupSlug === groupSlug)
		.map((practice) => {
			const practiceStanding = standingsBySlug.get(practice.slug);
			return {
				...practice,
				standing: practiceStanding?.standing,
				trend: trendsBySlug.get(practice.slug),
				nextStep: nextStepOf(practiceStanding),
			};
		});
	const reviewRunFeed: ReviewRunFeedState = activityQuery.isPending
		? { status: "loading" }
		: activityQuery.error
			? {
					status: "error",
					error: activityQuery.error,
					onRetry: () => void activityQuery.refetch(),
				}
			: {
					status: "ready",
					runs: loadedPages(activityQuery.data).flatMap((page) => page.content),
					hasMore: activityQuery.hasNextPage,
					isLoadingMore: activityQuery.isFetchingNextPage,
					onLoadMore: () => void activityQuery.fetchNextPage(),
				};

	const observationDetail: ObservationDetailState | undefined = openObservationId
		? {
				isLoading: observationQuery.isPending,
				detail: observationQuery.data,
				error: observationQuery.error ?? undefined,
			}
		: undefined;

	return (
		<PracticeGroupDetailPage
			group={group}
			standing={standing}
			practices={practices}
			groupTrend={trendQuery.data?.group}
			selectedPracticeSlug={selectedPracticeSlug}
			onSelectPractice={(practiceSlug) => {
				updateSelection({ practice: practiceSlug, observation: undefined });
			}}
			feed={reviewRunFeed}
			skeletonRows={ACTIVITY_PAGE_SIZE}
			openObservationId={openObservationId}
			observationDetail={observationDetail}
			onToggleObservation={(observationId) =>
				updateSelection({
					observation: openObservationId === observationId ? undefined : observationId,
				})
			}
			onRespond={(observation, response) => {
				const feedbackId = observation.feedbackId;
				if (!feedbackId) return;
				if (isEmptyFeedbackResponse(response)) {
					deleteResponseMutation.mutate({ path: { workspaceSlug, feedbackId } });
					return;
				}
				replaceResponseMutation.mutate({
					path: { workspaceSlug, feedbackId },
					body: response,
				});
			}}
			pendingFeedbackId={
				replaceResponseMutation.isPending
					? replaceResponseMutation.variables.path.feedbackId
					: deleteResponseMutation.isPending
						? deleteResponseMutation.variables.path.feedbackId
						: undefined
			}
			isLoading={
				groupsQuery.isPending ||
				statusesQuery.isPending ||
				practicesQuery.isPending ||
				standingsQuery.isPending ||
				trendQuery.isPending
			}
			error={
				groupsQuery.error ??
				statusesQuery.error ??
				practicesQuery.error ??
				standingsQuery.error ??
				trendQuery.error ??
				undefined
			}
			onRetry={() => {
				if (groupsQuery.isError) void groupsQuery.refetch();
				if (statusesQuery.isError) void statusesQuery.refetch();
				if (practicesQuery.isError) void practicesQuery.refetch();
				if (standingsQuery.isError) void standingsQuery.refetch();
				if (trendQuery.isError) void trendQuery.refetch();
			}}
			onBack={() =>
				void navigate({
					to: "/w/$workspaceSlug/user/$username",
					params: { workspaceSlug, username },
					search: {},
				})
			}
		/>
	);
}
