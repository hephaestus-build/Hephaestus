import { useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { useEffect } from "react";

import { getAllTeamsOptions } from "@/api/@tanstack/react-query.gen";
import {
	ACTIVITY_CATEGORY_DEFS,
	type ActivityCategory,
} from "@/components/activity/activity-kind-defs";
import { rangeStart } from "@/components/activity/activity-range";
import {
	parseActivityStack,
	WORKSPACE_ACTIVITY_LEVEL_KINDS,
	WORKSPACE_ACTIVITY_SEARCH_DEFAULTS,
	type WorkspaceActivitySearch,
	workspaceActivitySearchSchema,
} from "@/components/activity/activity-search";
import { ActivityDetailDrawer } from "@/components/activity/ActivityDetailDrawer";
import { workLogTitle } from "@/components/activity/work-log-markdown";
import { WorkspaceActivityPage } from "@/components/activity/WorkspaceActivityPage";
import { panelState } from "@/components/common/panel-state";
import { useNow } from "@/components/common/use-now";
import { useDetailStack } from "@/components/layout/detail-drawer/use-detail-stack";
import { visibleTeamPaths } from "@/components/teams/visible-team-tree";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import {
	useActivityOverview,
	useActivityWork,
	useMemberActivity,
	useOpenWork,
} from "@/hooks/use-activity";
import { workspaceHead } from "@/lib/page-title";
import { toScmProviderType } from "@/lib/provider/provider-terms";
import { useSearchState, carriedSearchParams } from "@/lib/search-params";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/workspace-activity")({
	component: WorkspaceActivity,
	head: workspaceHead("Workspace activity"),
	validateSearch: workspaceActivitySearchSchema,
	search: {
		// The team is this workspace's; only the range carries over to another workspace.
		middlewares: carriedSearchParams<WorkspaceActivitySearch>(
			["range"],
			WORKSPACE_ACTIVITY_SEARCH_DEFAULTS,
		),
	},
});

function WorkspaceActivity() {
	const { workspaceSlug } = Route.useParams();
	const search = Route.useSearch();
	const setSearch = useSearchState();
	const { workspaces } = useActiveWorkspaceSlug();
	const workspace = workspaces.find((candidate) => candidate.workspaceSlug === workspaceSlug);
	const providerType = toScmProviderType(workspace?.providerType);
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
	const members = useMemberActivity(scope);
	const memberUser =
		members.status === "ready"
			? members.members.find((member) => member.user.login === memberLogin)?.user
			: undefined;
	// A copy is headed by whose work it lists: the team, or the workspace, or the member opened.
	const pageOwner = team?.name ?? workspace?.displayName;
	const memberOwner = memberUser?.name ?? memberLogin;
	const copyOf = (
		category: ActivityCategory | undefined,
		owner: string | undefined,
		people: boolean,
	) => ({
		title: workLogTitle(
			category && ACTIVITY_CATEGORY_DEFS[category].label(providerType),
			owner ?? "Activity",
		),
		providerType,
		people,
	});
	const overview = useActivityOverview({ ...scope, range: search.range });
	const timeline = useActivityWork({ ...scope, copy: copyOf(undefined, pageOwner, true) });
	const categoryWorkLog = useActivityWork({
		...scope,
		kinds: pageCategory ? ACTIVITY_CATEGORY_DEFS[pageCategory].kinds : undefined,
		copy: copyOf(pageCategory, pageOwner, true),
		enabled: scopeKnown && pageCategory !== undefined,
	});

	// In the page's team scope, so a member's level counts what their row counts.
	const memberScope = {
		...scope,
		login: memberLogin,
		enabled: scopeKnown && memberLogin !== undefined,
	};
	const memberOpenWork = useOpenWork({ workspaceSlug, login: memberLogin });
	const memberOverview = useActivityOverview({ ...memberScope, range: search.range });
	const memberWorkLog = useActivityWork({
		...memberScope,
		copy: copyOf(undefined, memberOwner, false),
	});
	const memberCategoryWorkLog = useActivityWork({
		...memberScope,
		kinds: memberCategory ? ACTIVITY_CATEGORY_DEFS[memberCategory].kinds : undefined,
		copy: copyOf(memberCategory, memberOwner, false),
		enabled: memberScope.enabled && memberCategory !== undefined,
	});

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
				overview={overview}
				members={members}
				timeline={timeline}
			/>
			<ActivityDetailDrawer
				stack={detailStack}
				onClose={stackControls.close}
				pageLabel="Workspace activity"
				providerType={providerType}
				range={search.range}
				scope={team?.name}
				subject={{ people: "several" }}
				page={{ overview, categoryWorkLog }}
				member={{
					user: memberUser,
					openWork: memberOpenWork,
					overview: memberOverview,
					workLog: memberWorkLog,
					categoryWorkLog: memberCategoryWorkLog,
				}}
			/>
		</>
	);
}
