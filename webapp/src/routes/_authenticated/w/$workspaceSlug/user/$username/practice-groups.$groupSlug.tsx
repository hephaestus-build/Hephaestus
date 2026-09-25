import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { createFileRoute, Navigate, redirect, useNavigate } from "@tanstack/react-router";
import { z } from "zod";
import {
	getPracticeGroupTrendOptions,
	listGroupsOptions,
	listPracticeGroupReviewRunsInfiniteOptions,
	listPracticeGroupStandingsOptions,
	listPracticeStandingsOptions,
	listReviewedPracticesOptions,
} from "@/api/@tanstack/react-query.gen";
import { combinePanelStates, loadProps, queryLoadState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PracticeGroupDetailPage } from "@/components/profile/PracticeGroupDetailPage";
import { nextReviewRunPage, reviewRunFeedState } from "@/components/profile/review-runs";
import { useFeedbackResponseWrite } from "@/hooks/use-feedback-response-write";
import { useWorkspaceFeatures } from "@/hooks/use-workspace-features";
import { contributingPractices } from "@/lib/practice-standing";
import { useSearchPatch } from "@/lib/search-params";
import { resolveCurrentUser } from "@/runtime/auth/guard";

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
		getNextPageParam: nextReviewRunPage,
	});
	const { respond, pendingResponses } = useFeedbackResponseWrite(workspaceSlug, () => groupSlug);
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
			feed={reviewRunFeedState(activityQuery)}
			skeletonRows={ACTIVITY_PAGE_SIZE}
			onRespond={respond}
			pendingResponses={pendingResponses}
			{...loadProps(
				combinePanelStates(
					[groupsQuery, statusesQuery, practicesQuery, standingsQuery, trendQuery].map(
						queryLoadState,
					),
				),
			)}
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
