import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, useRouter } from "@tanstack/react-router";
import { toast } from "@/lib/toast";

import {
	getPublicActivityHiddenCountQueryKey,
	getUsersWithTeamsOptions,
	listHiddenContributorsQueryKey,
	updateActivityAutomationMutation,
} from "@/api/@tanstack/react-query.gen";
import { type Options, updatePublicActivityObjection } from "@/api/sdk.gen";
import type {
	ActivityPerson,
	ActivityTeam,
	UpdatePublicActivityObjectionData,
} from "@/api/types.gen";
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
import { stackInSearch } from "@/components/layout/detail-drawer/detail-stack";
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
import { filedUnder, pathNumber, usePendingMutationIds } from "@/hooks/use-pending-mutation-ids";
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

/** Where an account's classification is filed while it saves; the workspace follows it. */
const AUTOMATION_MUTATION_KEY = ["activity-automation"] as const;

/** Hiding takes a person out of the timeline as well. */
const HIDING_READS = new Set([...ACTIVITY_READS, "getActivityWork"]);

/**
 * One hide, or its Undo, with the person it is about as the level named them. The person is for
 * the screen only and never sent: the reply can arrive after the admin has opened someone else.
 */
interface VisibilityChange {
	request: Options<UpdatePublicActivityObjectionData>;
	subject: { login: string; name: string };
}

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
	const router = useRouter();
	const { workspaces } = useActiveWorkspaceSlug();
	const workspace = workspaces.find((candidate) => candidate.workspaceSlug === workspaceSlug);
	const providerType = toScmProviderType(workspace?.providerType);
	// A user view reads the page as the member does and changes nothing on their behalf.
	const readOnly = useAuth().userView !== undefined;
	const membership = useQuery(workspaceMembershipQueryOptions(workspaceSlug));
	const isAdmin = !readOnly && hasMinimumWorkspaceRole(membership.data?.role, "ADMIN");

	const period = periodFromSearch(search);
	const scope = { workspaceSlug, period, team: search.team, repo: search.repo };
	const detailStack = parseActivityStack(search.detail, WORKSPACE_ACTIVITY_LEVEL_KINDS);
	const peopleEnabled =
		search.view === "people" || detailStack.some(({ target }) => target.kind === "person");
	const people = useActivityPeople({ ...scope, enabled: peopleEnabled });
	const facets = useActivityFacets({ ...scope, enabled: peopleEnabled });
	const ready = people.status === "ready" ? people.people : undefined;

	const stackControls = useDetailStack();
	const targets = detailStack.map(({ target }) => target);
	const login = targets.find((target) => target.kind === "person")?.login;
	const category = targets.find((target) => target.kind === "activity")?.category;
	const person: ActivityPerson | undefined = ready
		? [...ready.people, ...ready.automation].find((candidate) => candidate.person.login === login)
		: undefined;

	// A copy is headed by whose work it lists: the team, the workspace, or the person opened.
	const timeline = useActivityWork({
		...scope,
		workspace: true,
		enabled: search.view === "timeline",
		copy: {
			title: workspaceWorkTitle(search.team, facets.data?.teams, workspace?.displayName),
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

	// Filed under this workspace, so a change keeps its own workspace and account when the admin
	// moves on, and each account here is busy only while a change of its own is out.
	const automationKey = [...AUTOMATION_MUTATION_KEY, workspaceSlug];
	const automation = useMutation({
		...filedUnder(automationKey, updateActivityAutomationMutation()),
		// Runs as the change is submitted, so the name is the account's as the page listed it then.
		onMutate: ({ path: { userId } }) => ({
			name: ready
				? [...ready.people, ...ready.automation].find((candidate) => candidate.person.id === userId)
						?.person.name
				: undefined,
		}),
		onSuccess: async (_data, { path, query }, subject) => {
			const name = subject.name ?? "The account";
			toast.success(
				query.treatAsAutomation ? `${name} is treated as automation` : `${name} counts as a person`,
				{ description: inWorkspace(path.workspaceSlug) },
			);
			await invalidateWorkspaceReads(queryClient, path.workspaceSlug, ACTIVITY_READS);
		},
		onError: (error, { path }, subject) =>
			toast.error(`We could not change how ${subject?.name ?? "this account"} counts`, {
				description: `${problemDetailOf(error)} ${inWorkspace(path.workspaceSlug)}`,
			}),
	});
	const automating = usePendingMutationIds(automationKey, (variables) =>
		pathNumber(variables, "userId"),
	);
	const automationPending = person !== undefined && automating.has(person.person.id);
	// A provider's bot account is automation whatever an admin says, so it has no action.
	const automationAction =
		isAdmin && person && person.kind !== "BOT" ? (
			<Button
				variant="outline"
				size="sm"
				disabled={automationPending}
				focusableWhenDisabled
				onClick={() =>
					automation.mutate({
						path: { workspaceSlug, userId: person.person.id },
						query: { treatAsAutomation: person.kind === "PERSON" },
					})
				}
			>
				{automationPending && <Spinner />}
				{automationLabel(person.kind, automationPending)}
			</Button>
		) : undefined;

	// Every outcome belongs to the workspace and person it was submitted for, not to the page the
	// admin is on when the reply arrives.
	const hiding = useMutation({
		mutationFn: async ({ request }: VisibilityChange) => {
			await updatePublicActivityObjection({ ...request, throwOnError: true });
		},
		onSuccess: async (_data, { request, subject }) => {
			const submitted = { path: { workspaceSlug: request.path.workspaceSlug } };
			await invalidateWorkspaceReads(queryClient, submitted.path.workspaceSlug, HIDING_READS);
			void queryClient.invalidateQueries({
				queryKey: getPublicActivityHiddenCountQueryKey(submitted),
			});
			void queryClient.invalidateQueries({ queryKey: listHiddenContributorsQueryKey(submitted) });
			const description = inWorkspace(submitted.path.workspaceSlug);
			if (request.query.hidden) {
				// The person has left the list their level opened from. Read after the reads above, since
				// the admin may have moved on meanwhile: only that person's level, on this page, closes.
				if (showsPerson(submitted.path.workspaceSlug, subject.login)) {
					stackControls.close(0);
				}
				toast.success(`${subject.name} is hidden from activity`, {
					description,
					duration: Number.POSITIVE_INFINITY,
					action: {
						label: "Undo",
						onClick: () =>
							hiding.mutate({ request: { ...request, query: { hidden: false } }, subject }),
					},
				});
			} else {
				toast.success(`${subject.name} is shown in activity again`, { description });
			}
		},
		onError: (error, { request, subject }) =>
			toast.error(
				request.query.hidden
					? `We could not hide ${subject.name} from activity`
					: `We could not show ${subject.name} in activity again`,
				{
					description: `${problemDetailOf(error)} ${inWorkspace(request.path.workspaceSlug)}`,
				},
			),
	});
	/** Whether the address shows this person's level on this page of that workspace right now. */
	const showsPerson = (submittedWorkspace: string, submittedLogin: string) =>
		router.state.matches.some(
			(match) =>
				match.routeId === Route.id &&
				match.params.workspaceSlug === submittedWorkspace &&
				parseActivityStack(stackInSearch(match.search.detail), WORKSPACE_ACTIVITY_LEVEL_KINDS).some(
					({ target }) => target.kind === "person" && target.login === submittedLogin,
				),
		);
	// Members are hidden and shown again under Members. This is for a person with no membership, who
	// has no account here to hide themselves with, so the members have to be known first. A provider's
	// bot and an account counted as automation never show on a page, so there is nothing to hide.
	const members = useQuery({
		...getUsersWithTeamsOptions({ path: { workspaceSlug } }),
		enabled: isAdmin && person?.kind === "PERSON",
	});
	const hideAction =
		isAdmin &&
		person?.kind === "PERSON" &&
		members.data?.some((member) => member.id === person.person.id) === false ? (
			<HidePersonAction
				name={person.person.name}
				// Busy only for this person here: a change for someone else does not hold their action.
				pending={
					hiding.isPending &&
					hiding.variables.request.path.workspaceSlug === workspaceSlug &&
					hiding.variables.request.path.userId === person.person.id
				}
				onConfirm={() =>
					hiding.mutate({
						request: {
							path: { workspaceSlug, userId: person.person.id },
							query: { hidden: true },
						},
						subject: { login: person.person.login, name: person.person.name },
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
				view={search.view}
				onViewChange={(view) => setView({ view })}
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
				facets={facets.data}
				facetsError={facets.error}
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

function workspaceWorkTitle(
	team: string | undefined,
	teams: ActivityTeam[] | undefined,
	workspaceName: string | undefined,
): string {
	const teamName = teamPaths(teams ?? []).find(({ key }) => key === team)?.label;
	return workLogTitle(teamName ?? team ?? workspaceName ?? "Activity");
}

/** Which workspace a notification is about, since the admin may be looking at another one by then. */
function inWorkspace(workspaceSlug: string): string {
	return `In workspace ${workspaceSlug}.`;
}

function automationLabel(kind: ActivityPerson["kind"], pending: boolean): string {
	if (pending) {
		return "Saving…";
	}
	return kind === "AUTOMATION" ? "Count as a person" : "Treat as automation";
}
