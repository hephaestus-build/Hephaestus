import { QueryClient } from "@tanstack/react-query";
import { act, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http, type HttpResponseResolver } from "msw";
import { describe, expect, it, vi } from "vitest";

import { getAllTeamsQueryKey, getWorkspaceQueryKey } from "@/api/@tanstack/react-query.gen";
import type { RepositoryInfo, TeamInfo } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { currentUser } from "@/mocks/fixtures/auth";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { QUERY_STALE_TIME_MS } from "@/runtime/tanstack-query/query-defaults";
import { type Deferred, deferred, pending } from "@/test/async";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

vi.setConfig({ testTimeout: 15_000 });

const SETTLE = { timeout: 5000 };

const BUG = { id: 31, name: "bug", color: "d73a4a" };

const WEB = {
	id: 21,
	name: "web",
	nameWithOwner: "acme/web",
	htmlUrl: "https://github.com/acme/web",
	hiddenFromContributions: false,
	labels: [BUG],
} satisfies Wire<RepositoryInfo>;

/** What the server holds for acme's one team, which the tests change. */
interface AcmeState {
	teamHidden: boolean;
	repositoryHidden: boolean;
	labelled: boolean;
}

function team(id: number, name: string, overrides: Partial<Wire<TeamInfo>> = {}): Wire<TeamInfo> {
	return {
		id,
		name,
		slug: name.toLowerCase().replaceAll(" ", "-"),
		hidden: false,
		labels: [],
		members: [],
		membershipCount: 0,
		repoPermissionCount: 0,
		repositories: [],
		...overrides,
	};
}

function acmeTeams({ teamHidden, repositoryHidden, labelled }: AcmeState): Wire<TeamInfo>[] {
	const repository = { ...WEB, hiddenFromContributions: repositoryHidden };
	return [
		team(1, "Frontend Team", {
			hidden: teamHidden,
			repositories: [repository],
			labels: labelled ? [{ ...BUG, repository }] : [],
		}),
	];
}

/** Beta's own team: rows of one workspace must never stand in for the other's. */
const BETA_TEAMS = [team(2, "Platform Team")];

const ACME = workspaceListItem("acme", { id: 1 });
const BETA = workspaceListItem("beta", { id: 2 });

const BETA_TEAMS_KEY = getAllTeamsQueryKey({ path: { workspaceSlug: "beta" } });

/** The production client's freshness: an unchanged read is not asked for again within 30 s. */
function productionClient() {
	return new QueryClient({
		defaultOptions: {
			queries: { staleTime: QUERY_STALE_TIME_MS, retry: false },
			mutations: { retry: false },
		},
	});
}

/**
 * Two workspaces an admin administers. Acme's teams answer from `state`, and `holdAcme` keeps later
 * reads waiting until the test releases them. Beta is read once: a later read could only come from
 * acme's change, so it never answers and stays visible as a read in flight.
 */
function serveTwoWorkspaces(state: AcmeState) {
	const reads = { acme: 0, beta: 0 };
	let gate: Promise<void> = Promise.resolve();
	server.use(
		http.get("*/workspaces", () => HttpResponse.json([ACME, BETA])),
		http.get("*/workspaces/acme", () => HttpResponse.json(ACME)),
		http.get("*/workspaces/beta", () => HttpResponse.json(BETA)),
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json(ownMembership("ADMIN")),
		),
		http.get("*/workspaces/:workspaceSlug/connections/catalog", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/members", () => HttpResponse.json([])),
		http.get("*/workspaces/acme/team", async () => {
			reads.acme += 1;
			await gate;
			return HttpResponse.json(acmeTeams(state));
		}),
		http.get("*/workspaces/beta/team", async () => {
			reads.beta += 1;
			return reads.beta === 1 ? HttpResponse.json(BETA_TEAMS) : pending<Response>();
		}),
	);
	return {
		reads,
		holdAcme: (): Deferred<void> => {
			const held = deferred();
			gate = held.promise;
			return held;
		},
	};
}

async function openWorkspace(
	router: ReturnType<typeof renderRouteAtWithRouter>["router"],
	workspaceSlug: string,
) {
	await act(async () => {
		await router.navigate({ to: "/w/$workspaceSlug/admin/teams", params: { workspaceSlug } });
	});
}

const failed = () =>
	HttpResponse.json({ status: 500, title: "Internal Server Error" }, { status: 500 });

type User = ReturnType<typeof userEvent.setup>;

async function openLabels(user: User) {
	await user.click(screen.getByRole("button", { name: "Configure labels for acme/web" }));
}

describe("a team change belongs to its workspace", () => {
	it.each([
		{
			change: "a team's visibility",
			serve: (resolver: HttpResponseResolver) =>
				http.post("*/workspaces/acme/team/1/visibility", resolver),
			start: async (user: User) => {
				await user.click(
					screen.getByRole("button", { name: "Hide Frontend Team from workspace activity" }),
				);
			},
			notice: "We could not update team visibility",
			unchanged: async () => {
				await screen.findByRole(
					"button",
					{ name: "Hide Frontend Team from workspace activity" },
					SETTLE,
				);
				expect(screen.queryByText("Hidden")).toBeNull();
			},
		},
		{
			change: "a repository's visibility",
			serve: (resolver: HttpResponseResolver) =>
				http.post("*/workspaces/acme/team/1/repositories/21/visibility", resolver),
			start: async (user: User) => {
				await user.click(screen.getByRole("button", { name: "Hide acme/web from contributions" }));
			},
			notice: "We could not update repository visibility",
			unchanged: async () => {
				await screen.findByRole("button", { name: "Hide acme/web from contributions" }, SETTLE);
				expect(screen.queryByText("Hidden in contributions")).toBeNull();
			},
		},
		{
			change: "a team's labels",
			serve: (resolver: HttpResponseResolver) =>
				http.post("*/workspaces/acme/teams/1/labels/21/bug", resolver),
			start: async (user: User) => {
				await openLabels(user);
				await user.click(await screen.findByRole("button", { name: "Add bug label" }));
			},
			notice: "We could not update the team’s labels",
			unchanged: async () => {
				await screen.findByRole("button", { name: "Configure labels for acme/web" }, SETTLE);
				expect(screen.queryByText("bug")).toBeNull();
			},
		},
	])(
		"says a failed change of $change for its own workspace after the admin switched, and leaves the other alone",
		async ({ serve, start, notice, unchanged }) => {
			const user = userEvent.setup();
			const response = deferred<Response>();
			const changes: string[] = [];
			serveTwoWorkspaces({ teamHidden: false, repositoryHidden: false, labelled: false });
			server.use(
				serve(async ({ request }) => {
					changes.push(new URL(request.url).pathname);
					return response.promise;
				}),
			);
			const { router, queryClient } = renderRouteAtWithRouter(
				"/w/acme/admin/teams",
				productionClient(),
			);
			try {
				await screen.findByRole("heading", { name: "Frontend Team" }, ROUTE_RENDER_WAIT);
				await start(user);
				await waitFor(() => expect(changes).toHaveLength(1));
				await openWorkspace(router, "beta");
				await screen.findByRole("heading", { name: "Platform Team" }, ROUTE_RENDER_WAIT);

				await act(async () => {
					response.resolve(failed());
				});
				await waitFor(() => expect(queryClient.isMutating()).toBe(0));
				// Beta keeps its own team: acme's rows never stand in for it, and it is not read again.
				screen.getByRole("heading", { name: "Platform Team" });
				expect(screen.queryByRole("heading", { name: "Frontend Team" })).toBeNull();
				expect(
					queryClient.getQueryData<TeamInfo[]>(BETA_TEAMS_KEY)?.map(({ name }) => name),
				).toStrictEqual(["Platform Team"]);
				expect(queryClient.getQueryState(BETA_TEAMS_KEY)).toMatchObject({
					fetchStatus: "idle",
					isInvalidated: false,
				});
				// The failure is said, and said to be acme's.
				await screen.findByText(notice, undefined, SETTLE);
				screen.getByText(/In workspace acme\.$/u);

				// Back in acme, nothing shows the change that failed.
				await openWorkspace(router, "acme");
				await unchanged();
			} finally {
				response.resolve(failed());
			}
		},
	);

	it.each([
		{
			change: "adding",
			labelled: false,
			toggle: "Add bug label",
			serve: (resolver: HttpResponseResolver) =>
				http.post("*/workspaces/acme/teams/1/labels/21/bug", resolver),
			settled: async () => {
				await screen.findByText("bug", undefined, SETTLE);
			},
		},
		{
			change: "removing",
			labelled: true,
			toggle: "Remove bug label",
			serve: (resolver: HttpResponseResolver) =>
				http.delete("*/workspaces/acme/teams/1/labels/31", resolver),
			settled: async () => {
				await waitFor(() => expect(screen.queryByText("bug")).toBeNull(), SETTLE);
			},
		},
	])(
		"reads acme's teams again after $change a label settles in another workspace",
		async ({ labelled, toggle, serve, settled }) => {
			const user = userEvent.setup();
			const response = deferred<Response>();
			const changes: string[] = [];
			const state = { teamHidden: false, repositoryHidden: false, labelled };
			serveTwoWorkspaces(state);
			server.use(
				serve(async ({ request }) => {
					changes.push(new URL(request.url).pathname);
					return response.promise;
				}),
			);
			const { router, queryClient } = renderRouteAtWithRouter(
				"/w/acme/admin/teams",
				productionClient(),
			);
			try {
				await screen.findByRole("heading", { name: "Frontend Team" }, ROUTE_RENDER_WAIT);
				await openLabels(user);
				await user.click(await screen.findByRole("button", { name: toggle }));
				await waitFor(() => expect(changes).toHaveLength(1));
				await openWorkspace(router, "beta");
				await screen.findByRole("heading", { name: "Platform Team" }, ROUTE_RENDER_WAIT);

				state.labelled = !labelled;
				await act(async () => {
					response.resolve(HttpResponse.json(acmeTeams(state)[0]));
				});
				await waitFor(() => expect(queryClient.isMutating()).toBe(0));
				expect(queryClient.getQueryState(BETA_TEAMS_KEY)).toMatchObject({
					fetchStatus: "idle",
					isInvalidated: false,
				});

				// Back within the freshness window, acme shows the label as the server now has it.
				await openWorkspace(router, "acme");
				await screen.findByRole("heading", { name: "Frontend Team" }, ROUTE_RENDER_WAIT);
				await settled();
			} finally {
				response.resolve(failed());
			}
		},
	);

	it("keeps a newer change through an older failed one, and reads the teams again", async () => {
		const user = userEvent.setup();
		const teamReply = deferred<Response>();
		const repositoryReply = deferred<Response>();
		const state = { teamHidden: false, repositoryHidden: false, labelled: false };
		const acme = serveTwoWorkspaces(state);
		server.use(
			http.post("*/workspaces/acme/team/1/visibility", async () => teamReply.promise),
			// The server records the repository change as it arrives; its answer is held.
			http.post("*/workspaces/acme/team/1/repositories/21/visibility", async () => {
				state.repositoryHidden = true;
				return repositoryReply.promise;
			}),
		);
		const { queryClient } = renderRouteAtWithRouter("/w/acme/admin/teams", productionClient());
		try {
			await user.click(
				await screen.findByRole(
					"button",
					{ name: "Hide Frontend Team from workspace activity" },
					ROUTE_RENDER_WAIT,
				),
			);
			await screen.findByRole("button", { name: "Show Frontend Team in workspace activity" });
			await user.click(screen.getByRole("button", { name: "Hide acme/web from contributions" }));
			await screen.findByText("Hidden in contributions");

			const refetch = acme.holdAcme();
			const readsBefore = acme.reads.acme;
			await act(async () => {
				teamReply.resolve(failed());
			});
			// The older failure does not take back the newer change; the teams are asked for instead.
			await waitFor(() => expect(acme.reads.acme).toBeGreaterThan(readsBefore), SETTLE);
			screen.getByText("Hidden in contributions");
			await screen.findByText("We could not update team visibility", undefined, SETTLE);

			refetch.resolve();
			await screen.findByRole(
				"button",
				{ name: "Hide Frontend Team from workspace activity" },
				SETTLE,
			);
			screen.getByText("Hidden in contributions");

			await act(async () => {
				repositoryReply.resolve(new HttpResponse(null, { status: 200 }));
			});
			await waitFor(() => expect(queryClient.isMutating()).toBe(0));
			screen.getByText("Hidden in contributions");
			screen.getByRole("button", { name: "Hide Frontend Team from workspace activity" });
		} finally {
			teamReply.resolve(failed());
			repositoryReply.resolve(new HttpResponse(null, { status: 200 }));
		}
	});
});

/**
 * Acme's teams answer with the status the test last chose, from `state`, after `hold` settles. Every
 * change the admin sends is recorded, so a read that changes nothing can show it sent nothing.
 */
function serveAcmeRead(state: AcmeState) {
	serveTwoWorkspaces(state);
	const read: { count: number; status: 200 | 403 | 503; hold: Promise<unknown> } = {
		count: 0,
		status: 200,
		hold: Promise.resolve(),
	};
	const changes: string[] = [];
	const record: HttpResponseResolver = ({ request }) => {
		changes.push(`${request.method} ${new URL(request.url).pathname}`);
		return new HttpResponse(null, { status: 200 });
	};
	server.use(
		http.get("*/workspaces/acme/team", async () => {
			read.count += 1;
			await read.hold;
			return read.status === 200
				? HttpResponse.json(acmeTeams(state))
				: HttpResponse.json(
						{ status: read.status, title: "Request failed" },
						{ status: read.status },
					);
		}),
		http.post("*/workspaces/acme/*", record),
		http.delete("*/workspaces/acme/*", record),
	);
	return { read, changes };
}

/** Reads acme's teams again through their generated key, as a returning admin does. */
async function rereadAcme(queryClient: QueryClient) {
	await act(async () => {
		await queryClient.invalidateQueries({
			queryKey: getAllTeamsQueryKey({ path: { workspaceSlug: "acme" } }),
		});
	});
}

/** Nothing the refused read said, and no control that acts on it, stays on screen. */
function expectTeamsWithdrawn() {
	expect(screen.queryByRole("heading", { name: "Frontend Team" })).toBeNull();
	expect(screen.queryByRole("button", { name: "Add bug label" })).toBeNull();
	expect(screen.queryByRole("button", { name: "Configure labels for acme/web" })).toBeNull();
	expect(
		screen.queryByRole("button", { name: "Hide Frontend Team from workspace activity" }),
	).toBeNull();
	expect(screen.queryByText("We could not refresh teams")).toBeNull();
	expect(screen.queryByText("No teams found")).toBeNull();
}

describe("a read of a workspace's teams", () => {
	it("keeps the teams, the open label picker and the search beside a failed refresh, until a read replaces them", async () => {
		const user = userEvent.setup();
		const state = { teamHidden: false, repositoryHidden: false, labelled: false };
		const { read, changes } = serveAcmeRead(state);
		const { queryClient } = renderRouteAtWithRouter(
			"/w/acme/admin/teams?q=Frontend",
			productionClient(),
		);
		await screen.findByRole("heading", { name: "Frontend Team" }, ROUTE_RENDER_WAIT);
		await openLabels(user);
		await screen.findByRole("button", { name: "Add bug label" });

		read.status = 503;
		await rereadAcme(queryClient);
		await screen.findByText("We could not refresh teams", undefined, SETTLE);
		screen.getByRole("heading", { name: "Frontend Team" });
		screen.getByRole("button", { name: "Add bug label" });
		expect(screen.getByLabelText<HTMLInputElement>("Search teams").value).toBe("Frontend");
		screen.getByRole("button", { name: "Retry" });
		expect(screen.queryByText("We could not load teams")).toBeNull();

		const held = deferred();
		read.hold = held.promise;
		read.status = 200;
		state.labelled = true;
		const readsBeforeRefresh = read.count;
		void queryClient.refetchQueries({
			queryKey: getAllTeamsQueryKey({ path: { workspaceSlug: "acme" } }),
		});
		const retrying = await screen.findByRole("button", { name: "Retrying…" }, SETTLE);
		expect(retrying.hasAttribute("disabled")).toBe(true);
		screen.getByRole("heading", { name: "Frontend Team" });
		screen.getByRole("button", { name: "Add bug label" });
		expect(screen.getByLabelText<HTMLInputElement>("Search teams").value).toBe("Frontend");

		await act(async () => {
			held.resolve();
		});
		await screen.findByRole("button", { name: "Remove bug label" }, SETTLE);
		expect(screen.queryByText("We could not refresh teams")).toBeNull();
		screen.getByRole("heading", { name: "Frontend Team" });
		expect(read.count).toBe(readsBeforeRefresh + 1);
		expect(changes).toStrictEqual([]);
	});

	it("withdraws refused teams and their open label picker, also through a later outage, and shows only a fresh read", async () => {
		const user = userEvent.setup();
		const state = { teamHidden: false, repositoryHidden: false, labelled: false };
		const { read, changes } = serveAcmeRead(state);
		const { queryClient } = renderRouteAtWithRouter("/w/acme/admin/teams", productionClient());
		await screen.findByRole("heading", { name: "Frontend Team" }, ROUTE_RENDER_WAIT);
		await openLabels(user);
		await screen.findByRole("button", { name: "Add bug label" });

		read.status = 403;
		await rereadAcme(queryClient);
		await screen.findByText("You do not have access to teams", undefined, SETTLE);
		expectTeamsWithdrawn();
		expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();

		read.status = 503;
		await rereadAcme(queryClient);
		await screen.findByText("We could not load teams", undefined, SETTLE);
		expectTeamsWithdrawn();

		read.status = 200;
		state.repositoryHidden = true;
		await user.click(screen.getByRole("button", { name: "Retry" }));
		await screen.findByRole("heading", { name: "Frontend Team" }, SETTLE);
		screen.getByText("Hidden in contributions");
		expect(screen.queryByRole("button", { name: "Add bug label" })).toBeNull();
		expect(read.count).toBe(4);
		expect(changes).toStrictEqual([]);
	});

	it("says the teams could not be read, not that there are none, when the first read fails", async () => {
		const user = userEvent.setup();
		const { read } = serveAcmeRead({ teamHidden: false, repositoryHidden: false, labelled: false });
		read.status = 503;
		renderRouteAtWithRouter("/w/acme/admin/teams", productionClient());

		await screen.findByText("We could not load teams", undefined, ROUTE_RENDER_WAIT);
		expect(screen.queryByText("No teams found")).toBeNull();
		expect(screen.queryByRole("heading", { name: "Frontend Team" })).toBeNull();

		read.status = 200;
		await user.click(screen.getByRole("button", { name: "Retry" }));
		await screen.findByRole("heading", { name: "Frontend Team" }, SETTLE);
		expect(screen.queryByText("We could not load teams")).toBeNull();
	});
});

it("renders verified nonmember target teams and withdraws them when its workspace read is refused", async () => {
	const requests: string[] = [];
	server.use(
		http.get("*/user", () => HttpResponse.json(currentUser)),
		http.get("*/workspaces", () => HttpResponse.json([ACME])),
		http.get("*/workspaces/tutors", () =>
			HttpResponse.json(workspaceListItem("tutors", { displayName: "Tutors" })),
		),
		http.get("*/workspaces/tutors/members/me", () =>
			HttpResponse.json({ status: 403 }, { status: 403 }),
		),
		http.get("*/workspaces/tutors/connections/catalog", () => HttpResponse.json([])),
		http.get("*/workspaces/tutors/team", ({ request }) => {
			requests.push(new URL(request.url).pathname);
			return HttpResponse.json([team(55, "Tutors Team")]);
		}),
	);
	const { router, queryClient } = renderRouteAtWithRouter("/w/tutors/admin/teams");
	await screen.findByRole("heading", { name: "Tutors Team" }, ROUTE_RENDER_WAIT);
	expect(router.state.location.pathname).toBe("/w/tutors/admin/teams");
	screen.getByText("Tutors administration");
	expect(requests).toHaveLength(1);
	expect(requests[0]).toMatch(/\/workspaces\/tutors\/team$/u);
	server.use(
		http.get("*/workspaces/tutors", () => HttpResponse.json({ status: 403 }, { status: 403 })),
	);
	await act(async () => {
		await queryClient.invalidateQueries({
			queryKey: getWorkspaceQueryKey({ path: { workspaceSlug: "tutors" } }),
		});
	});
	await screen.findByRole("alert");
	expect(screen.queryByRole("heading", { name: "Tutors Team" })).toBeNull();
});
