import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, Navigate, redirect, useNavigate } from "@tanstack/react-router";
import { toast } from "sonner";
import { z } from "zod";
import {
	deleteFeedbackResponseMutation,
	getPracticeGroupTrendOptions,
	listGroupsOptions,
	listPracticeGroupReviewRunsInfiniteOptions,
	listPracticeGroupReviewRunsInfiniteQueryKey,
	listPracticeGroupStandingsOptions,
	listPracticeStandingsOptions,
	listReviewedPracticesOptions,
	replaceFeedbackResponseMutation,
} from "@/api/@tanstack/react-query.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PracticeGroupDetailPage } from "@/components/profile/PracticeGroupDetailPage";
import { isEmptyFeedbackResponse, type ReviewRunFeedState } from "@/components/profile/review-runs";
import { useWorkspaceFeatures } from "@/hooks/use-workspace-features";
import { contributingPractices } from "@/lib/practice-standing";
import { problemDetailOf } from "@/lib/problem-detail";
import { useSearchPatch } from "@/lib/search-params";
import { hasText } from "@/lib/text";
import { resolveCurrentUser } from "@/runtime/auth/guard";
import { loadedPages } from "@/runtime/tanstack-query/spring-page";

const ACTIVITY_PAGE_SIZE = 10;

const practiceGroupDetailSearchSchema = z.object({
	practice: z.string().optional(),
});

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
	const { practice: selectedPracticeSlug } = Route.useSearch();
	const navigate = useNavigate({ from: Route.fullPath });
	const queryClient = useQueryClient();
	const updateSelection = useSearchPatch<{ practice?: string }>();

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
		getNextPageParam: (lastPage) =>
			lastPage.hasNext === true ? (lastPage.page ?? 0) + 1 : undefined,
	});
	const invalidateReviewRuns = async () =>
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
	const group = groupsQuery.data?.find((candidate) => candidate.slug === groupSlug);
	const standing = statusesQuery.data?.find((candidate) => candidate.groupSlug === groupSlug);
	const practices = practicesQuery.data
		? contributingPractices(
				groupSlug,
				practicesQuery.data,
				standingsQuery.data ?? [],
				trendQuery.data,
			)
		: undefined;
	const activityFailed = activityQuery.error != null;
	let reviewRunFeed: ReviewRunFeedState;
	if (activityQuery.isPending) {
		reviewRunFeed = { status: "loading" };
	} else if (activityFailed) {
		reviewRunFeed = {
			status: "error",
			error: activityQuery.error,
			onRetry: () => {
				void activityQuery.refetch();
			},
		};
	} else {
		reviewRunFeed = {
			status: "ready",
			runs: loadedPages(activityQuery.data).flatMap((page) => page.content),
			hasMore: activityQuery.hasNextPage,
			isLoadingMore: activityQuery.isFetchingNextPage,
			onLoadMore: () => {
				void activityQuery.fetchNextPage();
			},
		};
	}

	return (
		<PracticeGroupDetailPage
			group={group}
			standing={standing}
			practices={practices}
			groupTrend={trendQuery.data?.group}
			selectedPracticeSlug={selectedPracticeSlug}
			onSelectPractice={(practiceSlug) => {
				updateSelection({ practice: practiceSlug });
			}}
			feed={reviewRunFeed}
			skeletonRows={ACTIVITY_PAGE_SIZE}
			onRespond={(observation, response) => {
				const feedbackId = observation.feedbackResponse?.feedbackId;
				if (!hasText(feedbackId)) {
					return;
				}
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
				[replaceResponseMutation, deleteResponseMutation].find((mutation) => mutation.isPending)
					?.variables.path.feedbackId
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
				if (groupsQuery.isError) {
					void groupsQuery.refetch();
				}
				if (statusesQuery.isError) {
					void statusesQuery.refetch();
				}
				if (practicesQuery.isError) {
					void practicesQuery.refetch();
				}
				if (standingsQuery.isError) {
					void standingsQuery.refetch();
				}
				if (trendQuery.isError) {
					void trendQuery.refetch();
				}
			}}
			onBack={() => {
				void navigate({
					to: "/w/$workspaceSlug/user/$username",
					params: { workspaceSlug, username },
					search: {},
				});
			}}
		/>
	);
}
