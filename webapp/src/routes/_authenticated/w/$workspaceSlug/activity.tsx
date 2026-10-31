import { type UseQueryResult, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, Link } from "@tanstack/react-router";
import { toast } from "sonner";

import { resolveReviewContextOptions } from "@/api/@tanstack/react-query.gen";
import type { WorkspaceMembership } from "@/api/types.gen";
import { ACTIVITY_CATEGORY_DEFS } from "@/components/activity/activity-kind-defs";
import { periodFromSearch, periodSearch } from "@/components/activity/activity-period";
import {
	ACTIVITY_SEARCH_DEFAULTS,
	type ActivitySearch,
	activitySearchSchema,
	PERIOD_SEARCH_KEYS,
	parseActivityStack,
	SELF_ACTIVITY_LEVEL_KINDS,
} from "@/components/activity/activity-search";
import { ActivityDetailDrawer } from "@/components/activity/ActivityDetailDrawer";
import { type ActivityAccount, ActivityPage } from "@/components/activity/ActivityPage";
import { workLogTitle } from "@/components/activity/work-log-markdown";
import { detailSearch } from "@/components/layout/detail-drawer/detail-stack";
import { useDetailStack } from "@/components/layout/detail-drawer/use-detail-stack";
import { reviewLevel } from "@/components/practice-profile/practice-profile-search";
import { ReviewRequestedNotice } from "@/components/practice-trace/ReviewRequestedNotice";
import { buttonVariants } from "@/components/ui/button";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import { useActivityPerson, useActivityWork, useOpenWork } from "@/hooks/use-activity";
import { pathNumber, usePendingMutationIds } from "@/hooks/use-pending-mutation-ids";
import { useRequestPracticeReview } from "@/hooks/use-request-practice-review";
import { pageHead } from "@/lib/page-title";
import { problemDetailOf } from "@/lib/problem-detail";
import { type ProviderType, toScmProviderType } from "@/lib/provider/provider-terms";
import { useSearchState, carriedSearchParams } from "@/lib/search-params";
import { hasText } from "@/lib/text";
import { useAuth } from "@/runtime/auth/AuthContext";
import { workspaceMembershipQueryOptions } from "@/runtime/auth/guard";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/activity")({
	component: Activity,
	head: pageHead("Activity"),
	validateSearch: activitySearchSchema,
	search: {
		middlewares: carriedSearchParams<ActivitySearch>(PERIOD_SEARCH_KEYS, ACTIVITY_SEARCH_DEFAULTS),
	},
});

const REQUEST_REVIEW_MUTATION_KEY = ["activityRequestReview"];

const NOT_REQUESTABLE = "You cannot request a review of this work.";

/** A row's ask: the provider work it was asked from, and that work's address on the provider. */
interface WorkReviewAsk {
	path: { workspaceSlug: string; id: number };
	url: string | undefined;
}

/** Said the way a problem detail says it, so the failure toast reads it like any other. */
function notRequestable(reason: string | null): Error {
	const detail = reason ?? NOT_REQUESTABLE;
	return Object.assign(new Error(detail), { detail });
}

function Activity() {
	const { workspaceSlug } = Route.useParams();
	const search = Route.useSearch();
	const setSearch = useSearchState();
	const { userView } = useAuth();
	const { workspaces } = useActiveWorkspaceSlug();
	const workspace = workspaces.find((candidate) => candidate.workspaceSlug === workspaceSlug);
	const providerType = toScmProviderType(workspace?.providerType);
	const membership = useQuery(workspaceMembershipQueryOptions(workspaceSlug));
	const login = membership.data && providerLogin(membership.data, providerType);

	const detailStack = parseActivityStack(search.detail, SELF_ACTIVITY_LEVEL_KINDS);
	const stackControls = useDetailStack();
	const openCategory = detailStack.at(-1)?.target;

	const period = periodFromSearch(search);
	const scope = {
		workspaceSlug,
		period,
		own: true as const,
		enabled: login !== undefined,
	};
	const category = openCategory?.kind === "activity" ? openCategory.category : undefined;
	const openWork = useOpenWork({ workspaceSlug, login });
	const overview = useActivityPerson(scope);
	const timeline = useActivityWork({
		...scope,
		// Your own copy is headed by what it is; a member's or a team's names whose it is.
		copy: { title: "Activity", providerType, people: false },
	});
	const categoryWorkLog = useActivityWork({
		...scope,
		kinds: category ? ACTIVITY_CATEGORY_DEFS[category].kinds : undefined,
		copy: {
			title: workLogTitle(
				category ? ACTIVITY_CATEGORY_DEFS[category].label(providerType) : "Activity",
			),
			providerType,
			people: false,
		},
		enabled: scope.enabled && category !== undefined,
	});

	// A user view reads the developer's page; it never spends their workspace's budget.
	const mayRequestReview = userView === undefined && workspace?.practicesEnabled === true;
	const queryClient = useQueryClient();
	const reviewRequest = useRequestPracticeReview(workspaceSlug);
	const requestKey = [...REQUEST_REVIEW_MUTATION_KEY, workspaceSlug];
	// The row's work is the provider's; the review is asked for the practice item, which only the
	// work's review context names. One mutation spans both, so the row stays busy until the answer.
	const requestForWork = useMutation({
		mutationKey: requestKey,
		mutationFn: async ({ path, url }: WorkReviewAsk) => {
			if (!hasText(url)) {
				throw notRequestable(null);
			}
			const context = await queryClient.query({
				...resolveReviewContextOptions({
					path: { workspaceSlug: path.workspaceSlug },
					query: { url },
				}),
				staleTime: 0,
			});
			if (!context.requestAllowed || context.item === null) {
				throw notRequestable(context.reasonDescription);
			}
			// The ask reports its own answer and failure; only the lookup's failure is reported below.
			return reviewRequest.askAndSettle(context.item.id);
		},
		onError: (error) =>
			toast.error("We could not request the review", {
				description: problemDetailOf(error, "Try again in a moment."),
			}),
	});
	const requesting = usePendingMutationIds(requestKey, (variables) => pathNumber(variables, "id"));
	// The receipt is the latest row ask's own answer, read off the mutation that spans its lookup and
	// request. The inner ask follows whichever request was sent last, which can be another
	// workspace's that finished its lookup later.
	const answered =
		requestForWork.variables?.path.workspaceSlug === workspaceSlug
			? requestForWork.data
			: undefined;
	const acceptedReviewId = answered?.status === "SUBMITTED" ? answered.reviewId : null;

	return (
		<>
			<ActivityPage
				reviewNow={
					mayRequestReview
						? {
								onReviewNow: (work) =>
									requestForWork.mutate({
										path: { workspaceSlug, id: work.id },
										url: work.htmlUrl,
									}),
								requesting,
							}
						: undefined
				}
				notice={
					mayRequestReview && acceptedReviewId !== null ? (
						<ReviewRequestedNotice
							review={
								<Link
									to="/w/$workspaceSlug/practice-profile"
									params={{ workspaceSlug }}
									search={detailSearch(reviewLevel(acceptedReviewId))}
								/>
							}
						/>
					) : undefined
				}
				providerType={providerType}
				account={accountOf(membership, login, userView === undefined)}
				period={period}
				onPeriodChange={(next) => {
					void setSearch((previous) => ({ ...previous, ...periodSearch(next) }), {
						state: true,
						replace: true,
					});
				}}
				openWork={openWork}
				overview={overview}
				timeline={timeline}
			/>
			<ActivityDetailDrawer
				stack={login === undefined ? [] : detailStack}
				onClose={stackControls.close}
				pageLabel="Activity"
				providerType={providerType}
				period={period}
				owner={{ login, overview, workLog: timeline, categoryWorkLog }}
			/>
		</>
	);
}

// Activity uses this workspace provider's identity, including in user view; the sign-in identity can differ.
function providerLogin(
	membership: WorkspaceMembership,
	providerType: ProviderType,
): string | undefined {
	const identityLogin = membership.account.identities.find(
		(identity) => identity.provider === providerType,
	)?.login;
	return hasText(identityLogin) ? identityLogin : undefined;
}

/**
 * Whose activity the page reads. The link to connect an account is offered only to the account's own
 * reader: in a user view, the settings it would open are the viewer's.
 */
function accountOf(
	membership: UseQueryResult<WorkspaceMembership>,
	login: string | undefined,
	offerSettings: boolean,
): ActivityAccount {
	if (membership.isPending) {
		return { status: "loading" };
	}
	if (membership.isError) {
		return {
			status: "error",
			error: membership.error,
			onRetry: () => {
				void membership.refetch();
			},
		};
	}
	if (login !== undefined) {
		return { status: "ready", login };
	}
	return {
		status: "none",
		settingsLink: offerSettings ? (
			<Link
				to="/settings"
				hash="connected-accounts"
				className={buttonVariants({ variant: "outline" })}
			>
				Connect an account
			</Link>
		) : undefined,
	};
}
