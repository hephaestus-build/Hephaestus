import { type UseQueryResult, useQuery } from "@tanstack/react-query";
import {
	createFileRoute,
	Link,
	retainSearchParams,
	stripSearchParams,
} from "@tanstack/react-router";

import type { WorkspaceMembership } from "@/api/types.gen";
import { ACTIVITY_CATEGORY_DEFS } from "@/components/activity/activity-kind-defs";
import { rangeStart } from "@/components/activity/activity-range";
import {
	ACTIVITY_SEARCH_DEFAULTS,
	type ActivitySearch,
	activitySearchSchema,
	parseActivityStack,
	SELF_ACTIVITY_LEVEL_KINDS,
} from "@/components/activity/activity-search";
import { ActivityDetailDrawer } from "@/components/activity/ActivityDetailDrawer";
import { type ActivityAccount, ActivityPage } from "@/components/activity/ActivityPage";
import { useNow } from "@/components/common/use-now";
import { useDetailStack } from "@/components/layout/detail-drawer/use-detail-stack";
import { buttonVariants } from "@/components/ui/button";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import { useActivitySummary, useActivityTimeline, useOpenWork } from "@/hooks/use-activity";
import { workspaceHead } from "@/lib/page-title";
import { toScmProviderType } from "@/lib/provider/provider-terms";
import { useSearchState } from "@/lib/search-params";
import { hasText } from "@/lib/text";
import { useAuth } from "@/runtime/auth/AuthContext";
import { workspaceMembershipQueryOptions } from "@/runtime/auth/guard";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/activity")({
	component: Activity,
	head: workspaceHead("Activity"),
	validateSearch: activitySearchSchema,
	search: {
		middlewares: [
			retainSearchParams(["range"] satisfies (keyof ActivitySearch)[]),
			stripSearchParams(ACTIVITY_SEARCH_DEFAULTS),
		],
	},
});

function Activity() {
	const { workspaceSlug } = Route.useParams();
	const search = Route.useSearch();
	const setSearch = useSearchState();
	const { userView } = useAuth();
	// The login the server knows this account by here — in a user view, the viewed member's. Never the
	// sign-in name, which may be another linked identity's.
	const membership = useQuery(workspaceMembershipQueryOptions(workspaceSlug));
	const userLogin = membership.data?.userLogin;
	const login = hasText(userLogin) ? userLogin : undefined;
	const { workspaces } = useActiveWorkspaceSlug();
	const providerType = toScmProviderType(
		workspaces.find((workspace) => workspace.workspaceSlug === workspaceSlug)?.providerType,
	);

	const detailStack = parseActivityStack(search.detail, SELF_ACTIVITY_LEVEL_KINDS);
	const stackControls = useDetailStack(detailStack);
	const openCategory = detailStack.at(-1)?.target;

	const from = rangeStart(useNow(), search.range);
	const scope = { workspaceSlug, login, from, enabled: login !== undefined };
	const openWork = useOpenWork({ workspaceSlug, login });
	const summary = useActivitySummary(scope);
	const timeline = useActivityTimeline(scope);
	const categoryTimeline = useActivityTimeline({
		...scope,
		kinds:
			openCategory?.kind === "activity"
				? ACTIVITY_CATEGORY_DEFS[openCategory.category].kinds
				: undefined,
		enabled: scope.enabled && openCategory !== undefined,
	});

	return (
		<>
			<ActivityPage
				providerType={providerType}
				account={accountOf(membership, userView === undefined)}
				range={search.range}
				onRangeChange={(range) => {
					void setSearch((previous) => ({ ...previous, range }), { state: true, replace: true });
				}}
				openWork={openWork}
				summary={summary}
				timeline={timeline}
			/>
			<ActivityDetailDrawer
				stack={login === undefined ? [] : detailStack}
				onClose={stackControls.close}
				pageLabel="Activity"
				providerType={providerType}
				range={search.range}
				scope="by you"
				categoryTimeline={categoryTimeline}
			/>
		</>
	);
}

/**
 * Whose activity the page reads. The link to connect an account is offered only to the account's own
 * reader: in a user view, the settings it would open are the viewer's.
 */
function accountOf(
	membership: UseQueryResult<WorkspaceMembership>,
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
	const login = membership.data.userLogin;
	if (hasText(login)) {
		return { status: "ready", login };
	}
	return {
		status: "none",
		settingsLink: offerSettings ? (
			<Link
				to="/settings"
				hash="linked-accounts-heading"
				className={buttonVariants({ variant: "outline" })}
			>
				Connect an account
			</Link>
		) : undefined,
	};
}
