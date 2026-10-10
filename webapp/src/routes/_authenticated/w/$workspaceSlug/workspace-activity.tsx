import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { toast } from "sonner";

import {
	getPublicActivityHiddenCountQueryKey,
	updateActivityAutomationMutation,
	updatePublicActivityObjectionMutation,
} from "@/api/@tanstack/react-query.gen";
import type { ActivityPerson } from "@/api/types.gen";
import { ACTIVITY_CATEGORY_DEFS } from "@/components/activity/activity-kind-defs";
import {
	type ActivityPeriod,
	periodFromSearch,
	periodSearch,
} from "@/components/activity/activity-period";
import {
	PERIOD_SEARCH_KEYS,
	parseActivityStack,
	peopleDir,
	peopleOrder,
	WORKSPACE_ACTIVITY_LEVEL_KINDS,
	WORKSPACE_ACTIVITY_SEARCH_DEFAULTS,
	type WorkspaceActivitySearch,
	workspaceActivitySearchSchema,
} from "@/components/activity/activity-search";
import { ActivityDetailDrawer } from "@/components/activity/ActivityDetailDrawer";
import type { PeopleOrder } from "@/components/activity/ActivityPeopleTable";
import { HidePersonAction } from "@/components/activity/HidePersonAction";
import { teamPaths } from "@/components/activity/team-paths";
import { workLogTitle } from "@/components/activity/work-log-markdown";
import { WorkspaceActivityPage } from "@/components/activity/WorkspaceActivityPage";
import { useDetailStack } from "@/components/layout/detail-drawer/use-detail-stack";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import {
	useActivityFacets,
	useActivityPeople,
	useActivityPerson,
	useActivityWork,
} from "@/hooks/use-activity";
import { pageHead } from "@/lib/page-title";
import { problemDetailOf } from "@/lib/problem-detail";
import { toScmProviderType } from "@/lib/provider/provider-terms";
import { carriedSearchParams, nonEmpty, useSearchState } from "@/lib/search-params";
import { hasMinimumWorkspaceRole } from "@/lib/workspace-roles";
import { useAuth } from "@/runtime/auth/AuthContext";
import { workspaceMembershipQueryOptions } from "@/runtime/auth/guard";
import { invalidateWorkspaceReads } from "@/runtime/tanstack-query/invalidate-workspace-reads";

/** The reads that list an account among the people or the automation. */
const ACTIVITY_READS = new Set(["getActivityPeople", "getActivityPerson"]);

/** Hiding takes a person out of the timeline as well. */
const HIDING_READS = new Set([...ACTIVITY_READS, "getActivityWork"]);

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/workspace-activity")({
	component: WorkspaceActivity,
	head: pageHead("Workspace activity"),
	validateSearch: workspaceActivitySearchSchema,
	search: {
		// The team and the repositories are this workspace's; only the period carries over.
		middlewares: carriedSearchParams<WorkspaceActivitySearch>(
			PERIOD_SEARCH_KEYS,
			WORKSPACE_ACTIVITY_SEARCH_DEFAULTS,
		),
	},
});

function WorkspaceActivity() {
	const { workspaceSlug } = Route.useParams();
	const search = Route.useSearch();
	const setSearch = useSearchState();
	const queryClient = useQueryClient();
	const { workspaces } = useActiveWorkspaceSlug();
	const workspace = workspaces.find((candidate) => candidate.workspaceSlug === workspaceSlug);
	const providerType = toScmProviderType(workspace?.providerType);
	// A user view reads the page as the member does and changes nothing on their behalf.
	const readOnly = useAuth().userView !== undefined;
	const membership = useQuery(workspaceMembershipQueryOptions(workspaceSlug));
	const isAdmin = !readOnly && hasMinimumWorkspaceRole(membership.data?.role, "ADMIN");

	const period = periodFromSearch(search);
	const scope = { workspaceSlug, period, team: search.team, repo: search.repo };
	const people = useActivityPeople(scope);
	const facets = useActivityFacets(scope);
	const ready = people.status === "ready" ? people.people : undefined;
	const teamName = teamPaths(facets?.teams ?? []).find(({ key }) => key === search.team)?.label;

	const detailStack = parseActivityStack(search.detail, WORKSPACE_ACTIVITY_LEVEL_KINDS);
	const stackControls = useDetailStack(detailStack);
	const targets = detailStack.map(({ target }) => target);
	const login = targets.find((target) => target.kind === "person")?.login;
	const category = targets.find((target) => target.kind === "activity")?.category;
	const person: ActivityPerson | undefined = ready
		? [...ready.people, ...ready.automation].find((candidate) => candidate.person.login === login)
		: undefined;

	// A copy is headed by whose work it lists: the team, the workspace, or the person opened.
	const timeline = useActivityWork({
		...scope,
		copy: {
			title: workLogTitle(teamName ?? workspace?.displayName ?? "Activity"),
			providerType,
			people: true,
		},
	});
	// In the page's scope, so a person's level counts what their row counts.
	const personScope = { ...scope, userId: person?.person.id, enabled: person !== undefined };
	const personName = person?.person.name ?? login;
	const overview = useActivityPerson(personScope);
	const workLog = useActivityWork({
		...personScope,
		copy: { title: workLogTitle(personName), providerType, people: false },
	});
	const categoryWorkLog = useActivityWork({
		...personScope,
		kinds: category ? ACTIVITY_CATEGORY_DEFS[category].kinds : undefined,
		copy: {
			title: workLogTitle(
				category && ACTIVITY_CATEGORY_DEFS[category].label(providerType),
				personName,
			),
			providerType,
			people: false,
		},
		enabled: personScope.enabled && category !== undefined,
	});

	const automation = useMutation({
		...updateActivityAutomationMutation(),
		onSuccess: async (_data, { query }) => {
			toast.success(query.treatAsAutomation ? "Treated as automation" : "Counted as a person");
			await invalidateWorkspaceReads(queryClient, workspaceSlug, ACTIVITY_READS);
		},
		onError: (error) =>
			toast.error("We could not change how this account counts", {
				description: problemDetailOf(error),
			}),
	});
	// A provider's bot account is automation whatever an admin says, so it has no action.
	const automationAction =
		isAdmin && person && person.kind !== "BOT" ? (
			<Button
				variant="outline"
				size="sm"
				disabled={automation.isPending}
				focusableWhenDisabled
				onClick={() =>
					automation.mutate({
						path: { workspaceSlug, userId: person.person.id },
						query: { treatAsAutomation: person.kind === "PERSON" },
					})
				}
			>
				{automation.isPending && <Spinner />}
				{automationLabel(person.kind, automation.isPending)}
			</Button>
		) : undefined;

	const hiding = useMutation({
		...updatePublicActivityObjectionMutation(),
		onSuccess: async (_data, { path, query }) => {
			await invalidateWorkspaceReads(queryClient, workspaceSlug, HIDING_READS);
			void queryClient.invalidateQueries({
				queryKey: getPublicActivityHiddenCountQueryKey({ path: { workspaceSlug } }),
			});
			if (query.hidden) {
				// The person has left the list this level opened from.
				stackControls.close(0);
				toast.success(`${personName ?? "The person"} is hidden from activity`, {
					action: {
						label: "Undo",
						onClick: () => hiding.mutate({ path, query: { hidden: false } }),
					},
				});
			} else {
				toast.success("Shown in activity again");
			}
		},
		onError: (error) =>
			toast.error("We could not change who shows in activity", {
				description: problemDetailOf(error),
			}),
	});
	// A provider's bot and an account counted as automation never show on a page, so there is nothing
	// to hide.
	const hideAction =
		isAdmin && person?.kind === "PERSON" ? (
			<HidePersonAction
				name={person.person.name}
				pending={hiding.isPending}
				onConfirm={() =>
					hiding.mutate({
						path: { workspaceSlug, userId: person.person.id },
						query: { hidden: true },
					})
				}
			/>
		) : undefined;

	const setView = (view: Partial<WorkspaceActivitySearch>) => {
		void setSearch((previous) => ({ ...previous, ...view }), { state: true, replace: true });
	};

	return (
		<>
			<WorkspaceActivityPage
				providerType={providerType}
				period={period}
				onPeriodChange={(next: ActivityPeriod) => setView(periodSearch(next))}
				team={search.team}
				onTeamChange={(team) => setView({ team })}
				repo={search.repo ?? []}
				onRepoChange={(repo) => setView({ repo: nonEmpty(repo) })}
				order={peopleOrder(search.sort, search.dir)}
				onOrderChange={({ sort, desc }: PeopleOrder) =>
					setView({ sort, dir: peopleDir(sort, desc) })
				}
				people={people}
				facets={facets}
				timeline={timeline}
			/>
			<ActivityDetailDrawer
				stack={detailStack}
				onClose={stackControls.close}
				pageLabel="Workspace activity"
				providerType={providerType}
				period={period}
				owner={{
					login,
					user: person?.person,
					// Only the people of this period say who is not among them.
					absent:
						people.status === "ready" &&
						!people.stale &&
						login !== undefined &&
						person === undefined,
					// Without the people, nothing says whose activity the levels would read.
					overview: people.status === "error" ? people : overview,
					workLog: people.status === "error" ? people : workLog,
					categoryWorkLog: people.status === "error" ? people : categoryWorkLog,
					automationAction,
					hideAction,
				}}
			/>
		</>
	);
}

function automationLabel(kind: ActivityPerson["kind"], pending: boolean): string {
	if (pending) {
		return "Saving…";
	}
	return kind === "AUTOMATION" ? "Count as a person" : "Treat as automation";
}
