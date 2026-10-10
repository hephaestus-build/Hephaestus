import { createFileRoute, redirect, useRouter, useRouterState } from "@tanstack/react-router";

import { getPublicActivityOptions, listWorkspacesOptions } from "@/api/@tanstack/react-query.gen";
import type { PublicActivity } from "@/api/types.gen";
import { publicPeopleRows } from "@/components/activity/activity-people-rows";
import { periodFromSearch, periodQuery, periodSearch } from "@/components/activity/activity-period";
import {
	PEOPLE_SEARCH_DEFAULTS,
	type PeopleSearch,
	peopleDir,
	peopleOrder,
	peopleSearchSchema,
	PERIOD_SEARCH_KEYS,
} from "@/components/activity/activity-search";
import type { PeopleOrder } from "@/components/activity/ActivityPeopleTable";
import { PublicActivityPage } from "@/components/activity/PublicActivityPage";
import { PublicActivityOnboardingDialog } from "@/components/onboarding/PublicActivityOnboardingDialog";
import { Spinner } from "@/components/ui/spinner";
import { useLoginNavigation } from "@/hooks/use-login-navigation";
import { usePublicActivityOnboarding } from "@/hooks/use-public-activity-onboarding";
import { pageTitle } from "@/lib/page-title";
import { problemStatusOf } from "@/lib/problem-detail";
import { toScmProviderType } from "@/lib/provider/provider-terms";
import { carriedSearchParams, nonEmpty, useSearchState } from "@/lib/search-params";
import { useAuth } from "@/runtime/auth/AuthContext";
import { consentIsPending, resolveCurrentUser } from "@/runtime/auth/guard";

type PublicActivityLoad =
	| { status: "ready"; page: PublicActivity }
	| { status: "error"; error: unknown };

/**
 * The address of a workspace. Its members land on their home; everyone else sees the public activity
 * page where the workspace publishes one, and the sign-in page where it does not. The server gives
 * an unknown and a private workspace the same answer, so neither can be told from the other here.
 */
export const Route = createFileRoute("/w/$workspaceSlug/")({
	validateSearch: peopleSearchSchema,
	search: {
		middlewares: carriedSearchParams<PeopleSearch>(PERIOD_SEARCH_KEYS, PEOPLE_SEARCH_DEFAULTS),
	},
	beforeLoad: async ({ context, params, location }) => {
		// A public page needs no identity, so an identity that cannot be read is a visitor.
		const user = await resolveCurrentUser(context.queryClient).catch(() => null);
		if (!user) {
			return;
		}
		if (await consentIsPending(context.queryClient)) {
			throw redirect({
				to: "/consent",
				search: { returnTo: location.href },
				mask: { to: location.pathname, search: location.search, hash: location.hash },
			});
		}
		const workspaces = await context.queryClient
			.query(listWorkspacesOptions())
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
	},
	loaderDeps: ({ search: { range, from, to, repo } }) => ({ range, from, to, repo }),
	loader: async ({ context, params, deps, location }): Promise<PublicActivityLoad> => {
		const read = async (repo: string[] | undefined) =>
			context.queryClient.query(
				getPublicActivityOptions({
					path: { slug: params.workspaceSlug },
					query: { ...periodQuery(periodFromSearch(deps)), repo },
				}),
			);
		try {
			return { status: "ready", page: await read(deps.repo) };
		} catch (error) {
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
				// The workspace gate sends a signed-out visitor to sign in and a signed-in one to their own
				// workspace, the same for a workspace that is private as for one that does not exist.
				throw redirect({
					to: "/w/$workspaceSlug/activity",
					params,
					search: location.search,
					replace: true,
				});
			}
			return { status: "error", error };
		}
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
	pendingComponent: () => (
		<div className="flex h-96 items-center justify-center">
			<Spinner className="size-8" />
		</div>
	),
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
	const { isAuthenticated, userView } = useAuth();

	const page = load.status === "ready" ? load.page : undefined;
	const providerType = toScmProviderType(page?.providerType);
	const onboarding = usePublicActivityOnboarding({
		workspaceSlug,
		enabled: isAuthenticated && userView === undefined && page !== undefined,
	});
	const setView = (view: Partial<PeopleSearch>) => {
		void setSearch((previous) => ({ ...previous, ...view }), { replace: true });
	};

	return (
		<>
			<PublicActivityPage
				workspaceName={page?.workspaceName}
				providerType={providerType}
				viewer={
					isAuthenticated ? { status: "signed-in" } : { status: "signed-out", onSignIn: openLogin }
				}
				period={periodFromSearch(search)}
				onPeriodChange={(period) => setView(periodSearch(period))}
				order={peopleOrder(search.sort, search.dir)}
				onOrderChange={({ sort, desc }: PeopleOrder) =>
					setView({ sort, dir: peopleDir(sort, desc) })
				}
				repo={search.repo ?? []}
				onRepoChange={(repo) => setView({ repo: nonEmpty(repo) })}
				repositories={page?.repositories ?? []}
				coverage={page?.coverage}
				updating={updating}
				people={
					load.status === "ready"
						? { status: "ready", people: publicPeopleRows(load.page), stale: false }
						: {
								status: "error",
								error: load.error,
								onRetry: () => {
									void router.invalidate();
								},
							}
				}
			/>
			{page !== undefined && (
				<PublicActivityOnboardingDialog
					{...onboarding}
					workspaceName={page.workspaceName}
					providerType={providerType}
				/>
			)}
		</>
	);
}
