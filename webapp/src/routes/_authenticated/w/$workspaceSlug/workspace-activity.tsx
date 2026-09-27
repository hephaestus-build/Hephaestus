import { useQuery } from "@tanstack/react-query";
import { createFileRoute, retainSearchParams, stripSearchParams } from "@tanstack/react-router";
import { useEffect } from "react";

import { getAllTeamsOptions } from "@/api/@tanstack/react-query.gen";
import { ACTIVITY_CATEGORY_DEFS } from "@/components/activity/activity-kind-defs";
import { rangeStart } from "@/components/activity/activity-range";
import {
	parseActivityStack,
	WORKSPACE_ACTIVITY_LEVEL_KINDS,
	WORKSPACE_ACTIVITY_SEARCH_DEFAULTS,
	type WorkspaceActivitySearch,
	workspaceActivitySearchSchema,
} from "@/components/activity/activity-search";
import { ActivityDetailDrawer } from "@/components/activity/ActivityDetailDrawer";
import { WorkspaceActivityPage } from "@/components/activity/WorkspaceActivityPage";
import { panelState } from "@/components/common/panel-state";
import { useNow } from "@/components/common/use-now";
import { useDetailStack } from "@/components/layout/detail-drawer/use-detail-stack";
import { visibleTeamPaths } from "@/components/teams/visible-team-tree";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import {
	useActivitySummary,
	useActivityTimeline,
	useMemberActivity,
	useOpenWork,
} from "@/hooks/use-activity";
import { workspaceHead } from "@/lib/page-title";
import { toScmProviderType } from "@/lib/provider/provider-terms";
import { useSearchState } from "@/lib/search-params";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/workspace-activity")({
	component: WorkspaceActivity,
	head: workspaceHead("Workspace activity"),
	validateSearch: workspaceActivitySearchSchema,
	search: {
		middlewares: [
			// The team is this workspace's; only the range carries over to another workspace.
			retainSearchParams(["range"] satisfies (keyof WorkspaceActivitySearch)[]),
			stripSearchParams(WORKSPACE_ACTIVITY_SEARCH_DEFAULTS),
		],
	},
});

function WorkspaceActivity() {
	const { workspaceSlug } = Route.useParams();
	const search = Route.useSearch();
	const setSearch = useSearchState();
	const { workspaces } = useActiveWorkspaceSlug();
	const providerType = toScmProviderType(
		workspaces.find((workspace) => workspace.workspaceSlug === workspaceSlug)?.providerType,
	);
	const teams = panelState(useQuery(getAllTeamsOptions({ path: { workspaceSlug } })), (all) => ({
		status: "ready" as const,
		teams: visibleTeamPaths(all).map(({ team, path }) => ({ id: team.id, name: path })),
	}));

	// The URL's team is only read once it is one the page offers: a hidden or deleted team, or a
	// hand-typed id, is never asked about, and leaves the address once the teams say so.
	const team =
		teams.status === "ready"
			? teams.teams.find((candidate) => candidate.id === search.team)
			: undefined;
	const unknownTeam = teams.status === "ready" && search.team !== undefined && team === undefined;
	useEffect(() => {
		if (unknownTeam) {
			void setSearch((previous) => ({ ...previous, team: undefined }), { replace: true });
		}
	}, [unknownTeam, setSearch]);
	const scopeKnown = search.team === undefined || team !== undefined;

	const detailStack = parseActivityStack(search.detail, WORKSPACE_ACTIVITY_LEVEL_KINDS);
	const stackControls = useDetailStack(detailStack);
	const categories = detailStack
		.map(({ target }) => target)
		.filter((target) => target.kind === "activity");
	const pageCategory = categories.find((target) => target.member === undefined)?.category;
	const memberCategory = categories.find((target) => target.member !== undefined)?.category;
	const memberLogin = detailStack.find(({ target }) => target.kind === "member")?.id;

	const from = rangeStart(useNow(), search.range);
	const scope = { workspaceSlug, teamId: team?.id, from, enabled: scopeKnown };
	const summary = useActivitySummary(scope);
	const members = useMemberActivity(scope);
	const timeline = useActivityTimeline(scope);
	const categoryTimeline = useActivityTimeline({
		...scope,
		kinds: pageCategory ? ACTIVITY_CATEGORY_DEFS[pageCategory].kinds : undefined,
		enabled: scopeKnown && pageCategory !== undefined,
	});

	// In the page's team scope, so a member's level counts what their row counts.
	const memberScope = {
		...scope,
		login: memberLogin,
		enabled: scopeKnown && memberLogin !== undefined,
	};
	const memberOpenWork = useOpenWork({ workspaceSlug, login: memberLogin });
	const memberSummary = useActivitySummary(memberScope);
	const memberTimeline = useActivityTimeline(memberScope);
	const memberCategoryTimeline = useActivityTimeline({
		...memberScope,
		kinds: memberCategory ? ACTIVITY_CATEGORY_DEFS[memberCategory].kinds : undefined,
		enabled: memberScope.enabled && memberCategory !== undefined,
	});
	const memberUser =
		members.status === "ready"
			? members.members.find((member) => member.user.login === memberLogin)?.user
			: undefined;

	const setView = (view: Partial<WorkspaceActivitySearch>) => {
		void setSearch((previous) => ({ ...previous, ...view }), { state: true, replace: true });
	};

	return (
		<>
			<WorkspaceActivityPage
				providerType={providerType}
				range={search.range}
				onRangeChange={(range) => setView({ range })}
				teams={teams}
				teamId={team?.id}
				onTeamChange={(teamId) => setView({ team: teamId })}
				summary={summary}
				members={members}
				timeline={timeline}
			/>
			<ActivityDetailDrawer
				stack={detailStack}
				onClose={stackControls.close}
				pageLabel="Workspace activity"
				providerType={providerType}
				range={search.range}
				scope={team ? `in ${team.name}` : "in this workspace"}
				categoryTimeline={categoryTimeline}
				member={{
					user: memberUser,
					openWork: memberOpenWork,
					summary: memberSummary,
					timeline: memberTimeline,
					categoryTimeline: memberCategoryTimeline,
				}}
			/>
		</>
	);
}
