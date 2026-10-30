import { useQuery } from "@tanstack/react-query";
import { createFileRoute, redirect, useRouter, useRouterState } from "@tanstack/react-router";
import { currentTenantSlug } from "@/runtime/workspace-address";

import { getPublicActivityOptions, listWorkspacesOptions } from "@/api/@tanstack/react-query.gen";
import type { PublicActivity } from "@/api/types.gen";
import { publicPeopleRows } from "@/components/activity/activity-people-rows";
import { periodFromSearch, periodQuery, periodSearch } from "@/components/activity/activity-period";
import {
	parsePublicActivityStack,
	PUBLIC_ACTIVITY_SEARCH_DEFAULTS,
	type PeopleSearch,
	type PublicActivitySearch,
	peopleDir,
	peopleOrder,
	publicActivitySearchSchema,
	PERIOD_SEARCH_KEYS,
} from "@/components/activity/activity-search";
import type { PeopleOrder } from "@/components/activity/ActivityPeopleTable";
import {
	publicCategoryKinds,
	type PublicPersonActivityState,
} from "@/components/activity/public-person-activity";
import { PublicActivityPage } from "@/components/activity/PublicActivityPage";
import { PublicActivityPageSkeleton } from "@/components/activity/PublicActivityPageSkeleton";
import { queryErrorIsRetryable } from "@/components/common/QueryErrorAlert";
import { useDetailStack } from "@/components/layout/detail-drawer/use-detail-stack";
import { useLoginNavigation } from "@/hooks/use-login-navigation";
import { usePublicPersonActivity, usePublicPersonWork } from "@/hooks/use-public-person-activity";
import { pageTitle } from "@/lib/page-title";
import { problemStatusOf } from "@/lib/problem-detail";
import { toScmProviderType } from "@/lib/provider/provider-terms";
import { carriedSearchParams, nonEmpty, useSearchState } from "@/lib/search-params";
import { useAuth } from "@/runtime/auth/AuthContext";
import { consentIsPending, resolveCurrentUser } from "@/runtime/auth/guard";

type PublicActivityLoad =
	| { status: "ready"; page: PublicActivity }
	| { status: "error"; error: unknown };

function publicActivityOptions(
	slug: string,
	search: Pick<PublicActivitySearch, "range" | "from" | "to" | "repo">,
) {
	return getPublicActivityOptions({
		path: { slug },
		query: { ...periodQuery(periodFromSearch(search)), repo: search.repo },
	});
}

/**
 * The address of a workspace. Its members land on their home; everyone else sees the public activity
 * page where the workspace publishes one, and the sign-in page where it does not. The server gives
 * an unknown and a private workspace the same answer, so neither can be told from the other here.
 */
export const Route = createFileRoute("/w/$workspaceSlug/")({
	validateSearch: publicActivitySearchSchema,
	search: {
		middlewares: carriedSearchParams<PublicActivitySearch>(
			PERIOD_SEARCH_KEYS,
			PUBLIC_ACTIVITY_SEARCH_DEFAULTS,
		),
	},
	beforeLoad: async ({ context, params, location }) => {
		// A refused session is a visitor. A server that cannot answer shows the error surface, so a
		// signed-in member is never shown the visitor's page during an outage.
		const user = await resolveCurrentUser(context.queryClient);
		if (!user) {
			return { signedIn: false };
		}
		if (await consentIsPending(context.queryClient)) {
			throw redirect({
				to: "/consent",
				search: { returnTo: location.href },
				mask:
					currentTenantSlug() === undefined
						? { to: location.pathname, search: location.search, hash: location.hash }
						: undefined,
			});
		}
		const workspaces = await context.queryClient
			.query({ ...listWorkspacesOptions(), retry: false })
			.catch(() => undefined);
		const member = workspaces?.find(({ workspaceSlug }) => workspaceSlug === params.workspaceSlug);
		// A workspace list that cannot be fetched is not proof of no access: the workspace gate under
		// these pages answers for it, as it does for every other address of the workspace.
		if (workspaces === undefined || member !== undefined) {
			// The Practice profile where the workspace reviews practices, and Activity everywhere else.
			// The search travels with the redirect, since the app chrome reads its own params (a survey
			// link) from whatever page the home lands on.
			throw redirect({
				to:
					member?.practicesEnabled === true
						? "/w/$workspaceSlug/practice-profile"
						: "/w/$workspaceSlug/activity",
				params,
				search: location.search,
				replace: true,
			});
		}
		return { signedIn: true };
	},
	loaderDeps: ({ search: { range, from, to, repo } }) => ({ range, from, to, repo }),
	loader: {
		// Cached entry must finish its current read before its observer can retain that scope.
		staleReloadMode: "blocking",
		handler: async ({ context, params, deps, location }): Promise<PublicActivityLoad> => {
			const options = publicActivityOptions(params.workspaceSlug, deps);
			const read = async (repo: string[] | undefined) =>
				context.queryClient.query({
					...publicActivityOptions(params.workspaceSlug, { ...deps, repo }),
					retry: false,
				});
			try {
				return { status: "ready", page: await read(deps.repo) };
			} catch (error) {
				if (!queryErrorIsRetryable(error)) {
					// A later network failure cannot restore figures from before an access refusal.
					await context.queryClient.resetQueries({ queryKey: options.queryKey, exact: true });
				}
				if (problemStatusOf(error) === 404) {
					// A repository the workspace no longer lists is a 404 too. If the page reads without it,
					// the link was stale, not the workspace private.
					if (deps.repo !== undefined && (await read(undefined).catch(() => undefined))) {
						throw redirect({
							to: "/w/$workspaceSlug",
							params,
							search: { ...location.search, repo: undefined },
							replace: true,
						});
					}
					// A visitor signs in and comes back to this address, and a signed-in person reaches their own
					// workspace. Either way a private workspace answers as one that does not exist.
					if (context.signedIn) {
						throw redirect({
							to: "/w/$workspaceSlug/activity",
							params,
							search: location.search,
							replace: true,
						});
					}
					throw redirect({ to: "/login", search: { returnTo: location.href } });
				}
				return { status: "error", error };
			}
		},
	},
	// Nothing is indexed until the workspace says so, so a page that did not load stays out too.
	head: ({ loaderData }) => {
		const page = loaderData?.status === "ready" ? loaderData.page : undefined;
		return {
			meta: [
				{
					title: pageTitle(
						page === undefined ? "Public activity" : `${page.workspaceName} activity`,
					),
				},
				...(page?.allowSearchEngines === true ? [] : [{ name: "robots", content: "noindex" }]),
			],
		};
	},
	pendingComponent: PublicActivityPageSkeleton,
	component: PublicActivityRoute,
});

function PublicActivityRoute() {
	const { workspaceSlug } = Route.useParams();
	const search = Route.useSearch();
	const load = Route.useLoaderData();
	const router = useRouter();
	const setSearch = useSearchState();
	const openLogin = useLoginNavigation();
	// The loader holds the previous period's page until the next one arrives.
	const updating = useRouterState({ select: (state) => state.isLoading });
	const { isAuthenticated } = useAuth();

	// The loader owns requests; this observer owns only the exact query's mounted lifecycle.
	const query = useQuery({ ...publicActivityOptions(workspaceSlug, search), enabled: false });
	const retained =
		load.status === "error" &&
		queryErrorIsRetryable(load.error) &&
		(query.isRefetchError || query.isFetching) &&
		query.isFetchedAfterMount
			? query.data
			: undefined;
	const page = load.status === "ready" ? load.page : retained;
	const providerType = toScmProviderType(page?.providerType);
	const setView = (view: Partial<PeopleSearch>) => {
		void setSearch((previous) => ({ ...previous, ...view }), { replace: true });
	};
	const detailStack = parsePublicActivityStack(search.detail);
	const stackControls = useDetailStack();
	const personEntry = detailStack.find(({ target }) => target.kind === "person");
	const login = personEntry?.target.kind === "person" ? personEntry.target.login : undefined;
	const categoryEntry = detailStack.find(({ target }) => target.kind === "activity");
	const category =
		categoryEntry?.target.kind === "activity" ? categoryEntry.target.category : undefined;
	const scope = {
		workspaceSlug,
		login,
		period: periodFromSearch(search),
		repo: search.repo ?? [],
		enabled: page !== undefined && login !== undefined,
	};
	const person = usePublicPersonActivity(scope);
	const workLog = usePublicPersonWork(scope);
	const categoryWorkLog = usePublicPersonWork({
		...scope,
		enabled: scope.enabled && category !== undefined,
		kinds: category === undefined ? undefined : publicCategoryKinds(category),
	});
	const retry = () => {
		void router.invalidate();
	};
	// Without the page no person is read, so the page's failure and its Retry stand in the levels.
	// While a retry is out, the levels show their loading shape instead of the earlier failure.
	const detailPerson: PublicPersonActivityState =
		page === undefined && load.status === "error" && !updating
			? { status: "error", error: load.error, onRetry: retry, seedAllowed: false }
			: person;

	// Members never reach this page: the guard sends them to their workspace home. A visitor has no
	// membership to answer the public activity notice with, so the page asks them nothing.
	return (
		<PublicActivityPage
			workspaceName={page?.workspaceName}
			providerType={providerType}
			viewer={
				isAuthenticated ? { status: "signed-in" } : { status: "signed-out", onSignIn: openLogin }
			}
			period={periodFromSearch(search)}
			onPeriodChange={(period) => setView(periodSearch(period))}
			order={peopleOrder(search.sort, search.dir)}
			onOrderChange={({ sort, desc }: PeopleOrder) => setView({ sort, dir: peopleDir(sort, desc) })}
			repo={search.repo ?? []}
			onRepoChange={(repo) => setView({ repo: nonEmpty(repo) })}
			repositories={page?.repositories ?? []}
			coverage={page?.coverage}
			updating={updating}
			refreshError={
				retained !== undefined && load.status === "error"
					? {
							error: load.error,
							onRetry: retry,
							pending: updating,
						}
					: undefined
			}
			detail={{
				stack: detailStack,
				onClose: stackControls.close,
			}}
			personActivity={{ person: detailPerson, workLog, categoryWorkLog }}
			people={
				page === undefined
					? {
							status: "error",
							error: load.status === "error" ? load.error : undefined,
							onRetry: retry,
						}
					: { status: "ready", people: publicPeopleRows(page), stale: false }
			}
		/>
	);
}
