import { QueryClient } from "@tanstack/react-query";
import { createMemoryHistory, createRouter } from "@tanstack/react-router";
import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http, type PathParams } from "msw";
import { assert, describe, expect, it, vi } from "vitest";

import {
	getPublicActivityPersonQueryKey,
	getPublicActivityPersonWorkInfiniteQueryKey,
} from "@/api/@tanstack/react-query.gen";
import type {
	PublicActivity,
	PublicActivityPersonDetail,
	PublicActivityWorkPage,
	PublicActivityOnboardingRequest,
} from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { ROUTER_SEARCH } from "@/lib/router-search";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { unauthenticatedUser } from "@/mocks/handlers";
import { server } from "@/mocks/server";
import { routeTree } from "@/routeTree.gen";
import { workspaceRewrite } from "@/runtime/workspace-address";
import { deferred } from "@/test/async";
import { ObserverStub } from "@/test/observers";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

// A case mounts the whole app chrome, whose route modules are imported lazily.
vi.setConfig({ testTimeout: 30_000 });

const counts = {
	contributions: 5,
	pullRequestsOpened: 2,
	pullRequestsMerged: 1,
	pullRequestsReviewed: 3,
	peopleHelped: 2,
	issuesOpened: 0,
	activeWeeks: 1,
};

function person(login: string, name: string, overrides: Partial<typeof counts> = {}) {
	return {
		login,
		name,
		avatarUrl: "",
		profileUrl: `https://github.com/${login}`,
		counts: { ...counts, ...overrides },
		weeks: [{ start: "2026-09-21T00:00:00Z", contributions: counts.contributions }],
	};
}

function publicActivity(overrides: Partial<Wire<PublicActivity>> = {}): Wire<PublicActivity> {
	return {
		workspaceName: "Acme",
		providerType: "GITHUB",
		allowSearchEngines: false,
		from: "2026-09-01T00:00:00Z",
		to: "2026-10-01T00:00:00Z",
		coverage: { completeRepositories: 1, totalRepositories: 1 },
		highlights: { firstContributors: [], mostPeopleHelped: [] },
		repositories: [
			{ key: "acme/api", name: "api" },
			{ key: "acme/web", name: "web" },
		],
		people: [
			person("ada", "Ada Lovelace"),
			person("bob", "Bob Brenner", { contributions: 8, pullRequestsReviewed: 9 }),
		],
		...overrides,
	};
}

function personDetail(
	login = "ada",
	overrides: Partial<Wire<PublicActivityPersonDetail>> = {},
): Wire<PublicActivityPersonDetail> {
	return {
		from: "2026-09-01T00:00:00Z",
		to: "2026-10-01T00:00:00Z",
		person: {
			login,
			name: login === "ada" ? "Ada Lovelace" : "Bob Brenner",
			avatarUrl: "",
			htmlUrl: `https://github.com/${login}`,
		},
		counts,
		breakdown: { approvals: 2, changeRequests: 1, commentReviews: 0 },
		weeks: [
			{
				start: "2026-09-21T00:00:00Z",
				counts,
				breakdown: { approvals: 2, changeRequests: 1, commentReviews: 0 },
			},
		],
		repositories: [{ rowKey: "1", repository: { key: "acme/api", name: "api" }, counts }],
		supportedKinds: [
			"PULL_REQUEST_OPENED",
			"PULL_REQUEST_MERGED",
			"REVIEW_APPROVED",
			"REVIEW_CHANGES_REQUESTED",
			"REVIEW_COMMENTED",
			"ISSUE_OPENED",
		],
		...overrides,
	};
}

function publicWork(title = "Public work", cursor?: string): Wire<PublicActivityWorkPage> {
	return {
		content: [
			{
				id: "pr:42",
				lastOccurredAt: "2026-09-21T10:00:00Z",
				work: {
					type: "PULL_REQUEST",
					number: 42,
					title,
					state: "OPEN",
					isDraft: false,
					htmlUrl: "https://github.com/acme/api/pull/42",
					repository: { key: "acme/api", name: "api" },
				},
				actions: [{ kind: "REVIEW_APPROVED", count: 1 }],
			},
		],
		nextCursor: cursor,
	};
}

function publishPerson(reads: URL[] = [], workReads: URL[] = []) {
	server.use(
		http.get("*/public/workspaces/:slug/activity/people/:login", ({ request, params }) => {
			reads.push(new URL(request.url));
			return HttpResponse.json(personDetail(String(params.login)));
		}),
		http.get("*/public/workspaces/:slug/activity/people/:login/work", ({ request }) => {
			workReads.push(new URL(request.url));
			return HttpResponse.json(publicWork());
		}),
	);
}

function publish(page: Wire<PublicActivity> = publicActivity(), reads: URL[] = []) {
	server.use(
		http.get("*/public/workspaces/:slug/activity", ({ request }) => {
			reads.push(new URL(request.url));
			return HttpResponse.json(page);
		}),
	);
}

/** The people table's names in the order it shows them. */
function names(): string[] {
	return within(screen.getByRole("table", { name: "People" }))
		.getAllByRole("link")
		.map((link) => link.textContent);
}

const answerPersonDetail = () => HttpResponse.json(personDetail());
const workResponse: () => Response = () =>
	HttpResponse.json(publicWork("Previously published work"));

describe("the address of a workspace", () => {
	it("shows a signed-out visitor the public activity page of a workspace that publishes", async () => {
		server.use(unauthenticatedUser);
		publish();
		const { router } = renderRouteAtWithRouter("/w/acme");

		await screen.findByRole("heading", { name: "Acme activity" }, ROUTE_RENDER_WAIT);
		expect(names()).toStrictEqual(["Bob Brenner", "Ada Lovelace"]);
		within(screen.getByRole("main")).getByRole("button", { name: "Sign in" });
		expect(router.state.location.pathname).toBe("/w/acme");
	});

	// The server answers an unknown, a private and a switched-off workspace alike, so the page
	// has one way to learn that nothing is published: a not-found.
	it("sends a signed-out visitor of a workspace that publishes nothing to sign in, keeping the search", async () => {
		server.use(unauthenticatedUser);
		const { router } = renderRouteAtWithRouter("/w/private?range=1y");

		await waitFor(() => expect(router.state.location.pathname).toBe("/login"), ROUTE_RENDER_WAIT);
		expect(router.state.location.search.returnTo).toBe("/w/private?range=1y");
	});

	it("shows the error page, not a visitor's page, when the server cannot answer the identity", async () => {
		server.use(http.get("*/user", () => new HttpResponse(null, { status: 503 })));
		publish();
		const { router } = renderRouteAtWithRouter("/w/acme");

		await waitFor(
			() => expect(router.state.matches.some((match) => match.status === "error")).toBe(true),
			ROUTE_RENDER_WAIT,
		);
		expect(screen.queryByRole("heading", { name: "Acme activity" })).toBeNull();
		expect(screen.queryByRole("button", { name: "Sign in" })).toBeNull();
		expect(router.state.location.pathname).toBe("/w/acme");
	});

	it("drops a repository the workspace no longer lists, rather than treating the page as private", async () => {
		server.use(unauthenticatedUser);
		publish();
		// Added last, so it answers first: the read with the repository fails once.
		server.use(
			http.get(
				"*/public/workspaces/:slug/activity",
				() => HttpResponse.json({ status: 404, title: "Not Found" }, { status: 404 }),
				{ once: true },
			),
		);
		const { router } = renderRouteAtWithRouter("/w/acme?repo=acme/gone&sort=reviews");

		await screen.findByRole("heading", { name: "Acme activity" }, ROUTE_RENDER_WAIT);
		expect(router.state.location.href).toBe("/w/acme?sort=reviews");
	});

	it("reads the period, repositories and order from the address, and writes them back readable", async () => {
		const user = userEvent.setup();
		const reads: URL[] = [];
		server.use(unauthenticatedUser);
		publish(publicActivity(), reads);
		const { router } = renderRouteAtWithRouter("/w/acme?range=1y&repo=acme/api&sort=name");

		await screen.findByRole("heading", { name: "Acme activity" }, ROUTE_RENDER_WAIT);
		expect(names()).toStrictEqual(["Ada Lovelace", "Bob Brenner"]);
		expect(reads.at(-1)?.searchParams.get("range")).toBe("1y");
		expect(reads.at(-1)?.searchParams.getAll("repo")).toStrictEqual(["acme/api"]);

		await user.click(screen.getByRole("button", { name: /contributions/iu }));
		await waitFor(() => expect(router.state.location.search.sort).toBeUndefined());
		await user.click(screen.getByRole("button", { name: "30 days" }));
		await waitFor(() => expect(router.state.location.search.range).toBe("30d"));
		expect(router.state.location.href).toBe("/w/acme?range=30d&repo=acme/api");
		await waitFor(() => expect(reads.at(-1)?.searchParams.get("range")).toBe("30d"));
	});

	it("offers a retry when the page could not be read", async () => {
		const user = userEvent.setup();
		server.use(unauthenticatedUser);
		publish();
		// Added last, so it answers first: the page fails once and then reads.
		server.use(
			http.get("*/public/workspaces/:slug/activity", () => HttpResponse.error(), { once: true }),
		);
		renderRouteAtWithRouter("/w/acme");

		await screen.findByText("We could not load people", undefined, ROUTE_RENDER_WAIT);
		screen.getByRole("heading", { name: "Public activity" });
		expect(screen.queryByRole("table", { name: "People" })).toBeNull();
		await user.click(screen.getByRole("button", { name: /Retry/u }));
		await screen.findByRole("heading", { name: "Acme activity" });
		expect(names()).toHaveLength(2);
	});

	it("offers a retry inside a direct public person drawer when its parent page fails", async () => {
		const user = userEvent.setup();
		const parentRetry = deferred<Response>();
		const detailReads: URL[] = [];
		const workReads: URL[] = [];
		let parentReads = 0;
		server.use(unauthenticatedUser);
		publishPerson(detailReads, workReads);
		// The first read fails; the retry is held until the test answers it.
		server.use(
			http.get(
				"*/public/workspaces/:slug/activity",
				() => {
					parentReads += 1;
					return HttpResponse.json({ status: 503, title: "Service unavailable" }, { status: 503 });
				},
				{ once: true },
			),
			http.get("*/public/workspaces/:slug/activity", async () => {
				parentReads += 1;
				return parentRetry.promise;
			}),
		);
		const address = "/w/acme?range=30d&repo=acme/api&sort=name&detail=person:ada";
		const { router } = renderRouteAtWithRouter(address);
		try {
			const panel = await screen.findByRole("dialog", { name: "ada" }, ROUTE_RENDER_WAIT);
			await waitFor(() => expect(router.state.isLoading).toBe(false));
			expect(parentReads).toBe(1);
			expect(detailReads).toHaveLength(0);
			expect(workReads).toHaveLength(0);
			await within(panel).findAllByText("We could not load this person’s activity");
			expect(within(panel).queryByText("Loading this person’s activity…")).toBeNull();
			expect(within(panel).queryByText("Loading activity…")).toBeNull();
			const [retry] = within(panel).getAllByRole("button", { name: "Retry" });
			assert(retry);
			await user.click(retry);
			await waitFor(() => expect(parentReads).toBe(2));
			// While the retry is out, the level shows its loading shape, not the earlier failure.
			const retrying = screen.getByRole("dialog", { name: "ada" });
			await within(retrying).findByText("Loading this person’s activity…");
			expect(within(retrying).queryByText("We could not load this person’s activity")).toBeNull();
			expect(detailReads).toHaveLength(0);
			expect(workReads).toHaveLength(0);
			parentRetry.resolve(HttpResponse.json(publicActivity()));
			await screen.findByRole("dialog", { name: "Ada Lovelace" }, ROUTE_RENDER_WAIT);
			await screen.findByRole("link", { name: /Public work/u }, ROUTE_RENDER_WAIT);
			expect(detailReads).toHaveLength(1);
			expect(workReads).toHaveLength(1);
			expect(router.state.location.href).toBe(address);
		} finally {
			parentRetry.resolve(HttpResponse.json(publicActivity()));
		}
	});

	it("offers the same retry on a direct category level when its parent page cannot be reached", async () => {
		const user = userEvent.setup();
		const detailReads: URL[] = [];
		const workReads: URL[] = [];
		server.use(unauthenticatedUser);
		publishPerson(detailReads, workReads);
		publish();
		// Added last, so it answers first: the page fails once on the network and then reads.
		server.use(
			http.get("*/public/workspaces/:slug/activity", () => HttpResponse.error(), { once: true }),
		);
		const address = "/w/acme?range=30d&repo=acme/api&detail=person:ada&detail=activity:reviews";
		const { router } = renderRouteAtWithRouter(address);

		const level = await screen.findByRole("dialog", { name: "Reviews" }, ROUTE_RENDER_WAIT);
		await within(level).findByText("We could not load activity", undefined, ROUTE_RENDER_WAIT);
		expect(within(level).queryByText("Loading activity…")).toBeNull();
		expect(detailReads).toHaveLength(0);
		expect(workReads).toHaveLength(0);
		const [retry] = within(level).getAllByRole("button", { name: "Retry" });
		assert(retry);
		await user.click(retry);

		await screen.findByRole("link", { name: /Public work/u }, ROUTE_RENDER_WAIT);
		expect(workReads.some((url) => url.searchParams.getAll("kinds").length > 0)).toBe(true);
		expect(router.state.location.href).toBe(address);
	});

	it("says a direct person level is refused, and keeps it refused after a later network failure", async () => {
		const detailReads: URL[] = [];
		const workReads: URL[] = [];
		server.use(unauthenticatedUser);
		publish();
		publishPerson(detailReads, workReads);
		const { router } = renderRouteAtWithRouter("/w/acme?detail=person:ada");
		await screen.findByRole("dialog", { name: "Ada Lovelace" }, ROUTE_RENDER_WAIT);
		await screen.findByRole("link", { name: /Public work/u }, ROUTE_RENDER_WAIT);
		const reads = [detailReads.length, workReads.length];

		server.use(
			http.get("*/public/workspaces/:slug/activity", () =>
				HttpResponse.json({ status: 403 }, { status: 403 }),
			),
		);
		await act(async () => {
			await router.invalidate();
		});
		const refused = await screen.findByRole("dialog", { name: "ada" }, ROUTE_RENDER_WAIT);
		await within(refused).findAllByText("You do not have access to this person’s activity");
		expect(within(refused).queryByRole("button", { name: "Retry" })).toBeNull();
		expect(screen.queryByText("Ada Lovelace")).toBeNull();
		expect(screen.queryByRole("link", { name: /ada on GitHub/u })).toBeNull();
		expect(screen.queryByRole("link", { name: /Public work/u })).toBeNull();

		server.use(http.get("*/public/workspaces/:slug/activity", () => HttpResponse.error()));
		await act(async () => {
			await router.invalidate();
		});
		const failed = await screen.findByRole("dialog", { name: "ada" }, ROUTE_RENDER_WAIT);
		await within(failed).findAllByText("We could not load this person’s activity");
		expect(screen.queryByText("Ada Lovelace")).toBeNull();
		expect(screen.queryByRole("link", { name: /Public work/u })).toBeNull();
		expect([detailReads.length, workReads.length]).toStrictEqual(reads);
	});

	it("sends a direct person address of a workspace that publishes nothing to sign in, reading no person", async () => {
		const detailReads: URL[] = [];
		const workReads: URL[] = [];
		server.use(unauthenticatedUser);
		publishPerson(detailReads, workReads);
		const { router } = renderRouteAtWithRouter("/w/private?detail=person:ada");

		await waitFor(() => expect(router.state.location.pathname).toBe("/login"), ROUTE_RENDER_WAIT);
		expect(router.state.location.search.returnTo).toBe("/w/private?detail=person:ada");
		expect(screen.queryByRole("dialog", { name: "ada" })).toBeNull();
		expect(detailReads).toHaveLength(0);
		expect(workReads).toHaveLength(0);
	});

	// The router's own delays decide this: nothing for a read under a second, then the page's shape.
	it("shows the page's shape while a slow first read is out, then the page", async () => {
		const response = deferred<Response>();
		server.use(
			unauthenticatedUser,
			http.get("*/public/workspaces/:slug/activity", async () => response.promise),
		);
		renderRouteAtWithRouter("/w/acme");

		await screen.findByText("Loading public activity", undefined, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("heading", { name: "Acme activity" })).toBeNull();
		expect(screen.queryByRole("table", { name: "People" })).toBeNull();
		response.resolve(HttpResponse.json(publicActivity()));

		await screen.findByRole("heading", { name: "Acme activity" }, ROUTE_RENDER_WAIT);
		expect(screen.queryByText("Loading public activity")).toBeNull();
		expect(names()).toStrictEqual(["Bob Brenner", "Ada Lovelace"]);
	});

	it("shows a page that reads quickly without the loading shape in between", async () => {
		const loadingShown: boolean[] = [];
		const observer = new MutationObserver(() => {
			loadingShown.push(document.body.textContent.includes("Loading public activity"));
		});
		observer.observe(document.body, { childList: true, subtree: true });
		server.use(unauthenticatedUser);
		publish();
		renderRouteAtWithRouter("/w/acme");

		await screen.findByRole("heading", { name: "Acme activity" }, ROUTE_RENDER_WAIT);
		observer.disconnect();
		expect(loadingShown).not.toContain(true);
	});

	it("reads the selected public person and work independently, and returns to the same filters", async () => {
		const user = userEvent.setup();
		const reads: URL[] = [];
		const detailReads: URL[] = [];
		const workReads: URL[] = [];
		publishPerson(detailReads, workReads);
		server.use(unauthenticatedUser);
		publish(publicActivity(), reads);
		const address = "/w/acme?range=30d&repo=acme/api&sort=name";
		const { router } = renderRouteAtWithRouter(address);
		await screen.findByRole("heading", { name: "Acme activity" }, ROUTE_RENDER_WAIT);

		await user.click(screen.getByRole("link", { name: "Ada Lovelace" }));
		const panel = await screen.findByRole("dialog", { name: "Ada Lovelace" }, ROUTE_RENDER_WAIT);
		await waitFor(() => expect(router.state.location.search.detail).toBe("person:ada"));
		const totals = await within(panel).findByRole(
			"list",
			{ name: "Published totals" },
			ROUTE_RENDER_WAIT,
		);
		const contributions = within(totals)
			.getAllByRole("listitem")
			.find((tile) => within(tile).queryByText("Contributions") !== null);
		assert(contributions);
		expect(
			within(contributions).getByText(/^\d+$/u, { ignore: "[aria-hidden] *" }).textContent,
		).toBe("5");
		expect(within(panel).queryByRole("table", { name: "Contributions by week" })).toBeNull();
		await user.click(within(panel).getByRole("button", { name: "Show weekly contributions" }));
		const weeks = within(panel).getByRole("table", { name: "Contributions by week" });
		expect(within(weeks).getByRole("cell", { name: "5" }).textContent).toBe("5");
		expect(
			within(panel)
				.getByRole("link", { name: /ada on GitHub/u })
				.getAttribute("href"),
		).toBe("https://github.com/ada");
		expect(reads).toHaveLength(1);
		expect(detailReads).toHaveLength(1);
		expect(workReads).toHaveLength(1);
		expect(detailReads[0]?.searchParams.get("range")).toBe("30d");
		expect(workReads[0]?.searchParams.getAll("repo")).toStrictEqual(["acme/api"]);

		await act(async () => router.history.back());
		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
		expect(router.state.location.href).toBe(address);
		expect(reads).toHaveLength(1);
	});

	it("loads a direct person URL without a page row while its work read proceeds independently", async () => {
		const held = deferred<Response>();
		let workReads = 0;
		server.use(unauthenticatedUser);
		publish(publicActivity({ people: [] }));
		server.use(
			http.get("*/public/workspaces/:slug/activity/people/:login", async () => held.promise),
			http.get("*/public/workspaces/:slug/activity/people/:login/work", () => {
				workReads += 1;
				return HttpResponse.json(publicWork("Independent public work"));
			}),
		);
		renderRouteAtWithRouter("/w/acme?detail=person:ada");
		await waitFor(() => expect(workReads).toBe(1), ROUTE_RENDER_WAIT);
		await screen.findByRole("dialog", { name: "ada" }, ROUTE_RENDER_WAIT);
		const pending = screen.getByRole("dialog", { name: "ada" });
		within(pending).getByText("Loading this person’s activity…");
		expect(within(pending).queryByRole("link", { name: /ada on GitHub/u })).toBeNull();
		held.resolve(HttpResponse.json(personDetail()));
		await screen.findByRole("dialog", { name: "Ada Lovelace" }, ROUTE_RENDER_WAIT);
		await screen.findByRole("table", { name: "Repositories" }, ROUTE_RENDER_WAIT);
		await screen.findByRole("link", { name: /Independent public work/u }, ROUTE_RENDER_WAIT);
	});

	it("opens the selected person's review timeline with its actual public kinds and preserves navigation filters", async () => {
		const user = userEvent.setup();
		const workReads: URL[] = [];
		server.use(unauthenticatedUser);
		publish();
		publishPerson([], workReads);
		const address = "/w/acme?range=30d&repo=acme/api&detail=person:ada";
		const { router } = renderRouteAtWithRouter(address);
		const parent = await screen.findByRole("dialog", { name: "Ada Lovelace" }, ROUTE_RENDER_WAIT);
		const totals = await within(parent).findByRole(
			"list",
			{ name: "Published totals" },
			ROUTE_RENDER_WAIT,
		);
		await user.click(within(totals).getByRole("link", { name: /^Reviews/u }));
		await screen.findByRole("dialog", { name: "Reviews" }, ROUTE_RENDER_WAIT);
		await waitFor(() =>
			expect(workReads.some((url) => url.searchParams.getAll("kinds").length > 0)).toBe(true),
		);
		const selected = workReads.find((url) => url.searchParams.getAll("kinds").length > 0);
		expect(selected?.pathname).toContain("/people/ada/work");
		expect(selected?.searchParams.getAll("kinds")).toStrictEqual([
			"REVIEW_APPROVED",
			"REVIEW_CHANGES_REQUESTED",
			"REVIEW_COMMENTED",
		]);
		expect(selected?.searchParams.getAll("repo")).toStrictEqual(["acme/api"]);
		await user.keyboard("{Escape}");
		await waitFor(() => expect(screen.queryByRole("dialog", { name: "Reviews" })).toBeNull());
		expect(router.state.location.search.detail).toBe("person:ada");
		await user.click(
			within(screen.getByRole("dialog", { name: "Ada Lovelace" })).getByRole("button", {
				name: /^Close$/u,
			}),
		);
		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
		expect(router.state.location.search.repo).toBe("acme/api");
		expect(router.state.location.search.range).toBe("30d");
	});

	it("does not restore denied person data or old work after a later network failure", async () => {
		let detailResponse: () => Response = answerPersonDetail;
		server.use(unauthenticatedUser);
		publish();
		server.use(
			http.get("*/public/workspaces/:slug/activity/people/:login", () => detailResponse()),
			http.get("*/public/workspaces/:slug/activity/people/:login/work", () => workResponse()),
		);
		const { queryClient, router } = renderRouteAtWithRouter("/w/acme?detail=person:ada");
		await screen.findByRole("link", { name: /Previously published work/u }, ROUTE_RENDER_WAIT);
		const detailKey = getPublicActivityPersonQueryKey({
			path: { slug: "acme", login: "ada" },
			query: { range: "90d" },
		});
		const workKey = getPublicActivityPersonWorkInfiniteQueryKey({
			path: { slug: "acme", login: "ada" },
			query: { range: "90d", size: 30 },
		});
		detailResponse = () => HttpResponse.json({ status: 404, title: "Not Found" }, { status: 404 });
		await act(async () => {
			await queryClient.invalidateQueries({ queryKey: detailKey, exact: true });
		});
		await screen.findAllByText("We could not find this person’s activity", {}, ROUTE_RENDER_WAIT);
		await waitFor(() => expect(queryClient.getQueryData(detailKey)).toBeUndefined());
		await waitFor(() => expect(queryClient.getQueryData(workKey)).toBeDefined());
		expect(screen.queryByRole("link", { name: /Previously published work/u })).toBeNull();
		expect(screen.queryByRole("link", { name: /ada on GitHub/u })).toBeNull();
		detailResponse = () => HttpResponse.error();
		await act(async () => {
			await queryClient.invalidateQueries({ queryKey: detailKey, exact: true });
		});
		await screen.findByText("We could not load this person’s activity", {}, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("link", { name: /Previously published work/u })).toBeNull();
		expect(screen.queryByRole("link", { name: /ada on GitHub/u })).toBeNull();
		await act(async () => {
			await router.navigate({
				to: "/w/$workspaceSlug",
				params: { workspaceSlug: "acme" },
				search: {},
			});
		});
		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
		await act(async () => {
			await router.navigate({
				to: "/w/$workspaceSlug",
				params: { workspaceSlug: "acme" },
				search: { detail: ["person:ada"] },
			});
		});
		await screen.findByText("We could not load this person’s activity", {}, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("link", { name: /Previously published work/u })).toBeNull();
	});
});

/** The robots tags the page asks for once its route has loaded. */
async function robotsAt(url: string) {
	const router = createRouter({
		...ROUTER_SEARCH,
		routeTree,
		history: createMemoryHistory({ initialEntries: [url] }),
		context: { queryClient: new QueryClient(), auth: undefined },
	});
	await router.load();
	return router.state.matches
		.flatMap(({ meta }) => meta ?? [])
		.filter((tag) => tag?.name === "robots");
}

describe("search engines", () => {
	it("are asked to stay away unless the workspace allows them", async () => {
		server.use(unauthenticatedUser);
		publish();

		await expect(robotsAt("/w/acme")).resolves.toStrictEqual([
			{ name: "robots", content: "noindex" },
		]);
	});

	it("stay away from a page that did not load", async () => {
		server.use(unauthenticatedUser);
		server.use(http.get("*/public/workspaces/:slug/activity", () => HttpResponse.error()));

		await expect(robotsAt("/w/acme")).resolves.toStrictEqual([
			{ name: "robots", content: "noindex" },
		]);
	});

	it("may list the page when the workspace allows them", async () => {
		server.use(unauthenticatedUser);
		publish(publicActivity({ allowSearchEngines: true }));

		await expect(robotsAt("/w/acme")).resolves.toStrictEqual([]);
	});
});

describe("refreshing public activity", () => {
	it("keeps the active range through a failed refresh and disables Retry while it reloads", async () => {
		server.use(unauthenticatedUser);
		publish();
		const { router } = renderRouteAtWithRouter("/w/acme?sort=name");
		await screen.findByRole("heading", { name: "Acme activity" }, ROUTE_RENDER_WAIT);
		server.use(http.get("*/public/workspaces/:slug/activity", () => HttpResponse.error()));
		await act(async () => {
			await router.invalidate();
		});
		expect(names()).toStrictEqual(["Ada Lovelace", "Bob Brenner"]);
		screen.getByText("We could not refresh activity");

		const next = deferred<Wire<PublicActivity>>();
		server.use(
			http.get("*/public/workspaces/:slug/activity", async () =>
				HttpResponse.json(await next.promise),
			),
		);
		await userEvent.setup().click(screen.getByRole("button", { name: "Retry" }));
		await waitFor(() =>
			expect(screen.getByRole<HTMLButtonElement>("button", { name: "Retrying…" }).disabled).toBe(
				true,
			),
		);
		expect(names()).toStrictEqual(["Ada Lovelace", "Bob Brenner"]);
		await act(async () => {
			next.resolve(publicActivity({ people: [person("bob", "Bob Brenner")] }));
		});
		await waitFor(() => expect(screen.queryByText("We could not refresh activity")).toBeNull());
		expect(names()).toStrictEqual(["Bob Brenner"]);
	});

	it.each([429, 503])("retains the active figures on a retryable %s refresh", async (status) => {
		server.use(unauthenticatedUser);
		publish();
		const { router } = renderRouteAtWithRouter("/w/acme?sort=name");
		await screen.findByRole("heading", { name: "Acme activity" }, ROUTE_RENDER_WAIT);
		server.use(
			http.get("*/public/workspaces/:slug/activity", () =>
				HttpResponse.json({ status }, { status }),
			),
		);
		await act(async () => {
			await router.invalidate();
		});
		expect(names()).toStrictEqual(["Ada Lovelace", "Bob Brenner"]);
		screen.getByText("We could not refresh activity");
	});

	it.each([401, 403])(
		"does not restore pre-refusal figures after %s and a later network failure",
		async (status) => {
			server.use(unauthenticatedUser);
			publish();
			const { router } = renderRouteAtWithRouter("/w/acme");
			await screen.findByRole("heading", { name: "Acme activity" }, ROUTE_RENDER_WAIT);
			server.use(
				http.get("*/public/workspaces/:slug/activity", () =>
					HttpResponse.json({ status }, { status }),
				),
			);
			await act(async () => {
				await router.invalidate();
			});
			expect(screen.queryByRole("heading", { name: "Acme activity" })).toBeNull();
			server.use(http.get("*/public/workspaces/:slug/activity", () => HttpResponse.error()));
			await act(async () => {
				await router.invalidate();
			});
			expect(screen.queryByRole("heading", { name: "Acme activity" })).toBeNull();
			expect(screen.queryByText("Ada Lovelace")).toBeNull();
		},
	);

	it("redirects on publication removal instead of retaining its earlier figures", async () => {
		server.use(unauthenticatedUser);
		publish();
		const { router } = renderRouteAtWithRouter("/w/acme");
		await screen.findByRole("heading", { name: "Acme activity" }, ROUTE_RENDER_WAIT);
		server.use(
			http.get("*/public/workspaces/:slug/activity", () =>
				HttpResponse.json({ status: 404 }, { status: 404 }),
			),
		);
		await act(async () => {
			await router.invalidate();
		});
		await waitFor(() => expect(router.state.location.pathname).toBe("/login"));
		expect(screen.queryByText("Ada Lovelace")).toBeNull();
	});

	it.each([{ range: "30d" as const }, { repo: ["acme/api"] }])(
		"does not borrow a displayed range for another failed scope %j",
		async (search) => {
			server.use(unauthenticatedUser);
			publish();
			const { router } = renderRouteAtWithRouter("/w/acme");
			await screen.findByRole("heading", { name: "Acme activity" }, ROUTE_RENDER_WAIT);
			server.use(http.get("*/public/workspaces/:slug/activity", () => HttpResponse.error()));
			await act(async () => {
				await router.navigate({
					to: "/w/$workspaceSlug",
					params: { workspaceSlug: "acme" },
					search,
				});
			});
			expect(screen.queryByRole("heading", { name: "Acme activity" })).toBeNull();
			expect(screen.queryByText("Ada Lovelace")).toBeNull();
		},
	);

	it("does not reuse a cached workspace on a failed return from another workspace", async () => {
		server.use(unauthenticatedUser);
		publish();
		const { router } = renderRouteAtWithRouter("/w/acme");
		await screen.findByRole("heading", { name: "Acme activity" }, ROUTE_RENDER_WAIT);
		publish(
			publicActivity({ workspaceName: "Other", people: [person("other", "Other Developer")] }),
		);
		await act(async () => {
			await router.navigate({ to: "/w/$workspaceSlug", params: { workspaceSlug: "other" } });
		});
		await screen.findByRole("heading", { name: "Other activity" });
		server.use(http.get("*/public/workspaces/:slug/activity", () => HttpResponse.error()));
		await act(async () => {
			await router.navigate({ to: "/w/$workspaceSlug", params: { workspaceSlug: "acme" } });
		});
		expect(screen.queryByRole("heading", { name: "Acme activity" })).toBeNull();
		expect(screen.queryByText("Ada Lovelace")).toBeNull();
		expect(screen.queryByText("Other Developer")).toBeNull();
	});
});

/** The page, the person's own workspace, and the answers the first-visit step records. */
function visit() {
	const answers: unknown[] = [];
	const requests: URL[] = [];
	let hidden = false;
	server.use(
		http.get("*/public/workspaces/:slug/activity", ({ request }) => {
			requests.push(new URL(request.url));
			return HttpResponse.json(
				publicActivity({
					people: hidden
						? [person("bob", "Bob Brenner")]
						: [person("ada", "Ada Lovelace"), person("bob", "Bob Brenner")],
				}),
			);
		}),
		http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("other")])),
		http.get("*/workspaces/other/members/me", () => HttpResponse.json(ownMembership("MEMBER"))),
		http.get("*/user/public-activity/workspaces/acme/onboarding", () =>
			HttpResponse.json({ seen: answers.length > 0, visible: !hidden }),
		),
		http.put<PathParams, PublicActivityOnboardingRequest>(
			"*/user/public-activity/workspaces/acme/onboarding",
			async ({ request }) => {
				const body = await request.json();
				answers.push(body);
				hidden = !body.visible;
				return HttpResponse.json({ seen: true, visible: body.visible });
			},
		),
	);
	return { answers, requests };
}

describe("a signed-in person who is not a member", () => {
	it("sees the page, and the step to show or hide themselves", async () => {
		const user = userEvent.setup();
		const { answers } = visit();
		const { router } = renderRouteAtWithRouter("/w/acme");

		const step = await screen.findByRole("alertdialog", {}, ROUTE_RENDER_WAIT);
		expect(router.state.location.pathname).toBe("/w/acme");
		// The step is modal, so the page behind it is out of the accessibility tree.
		const settings = within(screen.getByRole("main", { hidden: true })).getByRole("link", {
			name: "User settings",
			hidden: true,
		});
		expect(settings.getAttribute("href")).toBe("/settings");
		await user.click(within(step).getByRole("button", { name: "Hide me" }));

		await waitFor(() => expect(answers).toStrictEqual([{ visible: false }]));
		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
	});

	it("no longer sees their own row once they hide, without reloading", async () => {
		const user = userEvent.setup();
		const { requests } = visit();
		renderRouteAtWithRouter("/w/acme");

		const step = await screen.findByRole("alertdialog", {}, ROUTE_RENDER_WAIT);
		expect(requests).toHaveLength(1);
		await user.click(within(step).getByRole("button", { name: "Hide me" }));

		await waitFor(() => expect(requests.length).toBeGreaterThan(1));
		await waitFor(() => expect(names()).toStrictEqual(["Bob Brenner"]));
	});

	it("can leave the step for this visit with Escape, and go on while a save never answers", async () => {
		const user = userEvent.setup();
		// Never answered: a save that settled later would read the page again, into the next case.
		const hangs = deferred<Response>();
		visit();
		server.use(
			http.put("*/user/public-activity/workspaces/acme/onboarding", async () => hangs.promise),
		);
		renderRouteAtWithRouter("/w/acme");

		const step = await screen.findByRole("alertdialog", {}, ROUTE_RENDER_WAIT);
		await user.click(within(step).getByRole("button", { name: "Hide me" }));
		await user.click(within(step).getByRole("button", { name: "Decide later" }));

		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
	});

	it("has no active workspace here, so the chrome reads nothing the server would refuse", async () => {
		const { requests } = visit();
		const paths: string[] = [];
		server.events.on("request:start", ({ request }) => {
			paths.push(new URL(request.url).pathname);
		});
		renderRouteAtWithRouter("/w/acme");

		await screen.findByRole("alertdialog", {}, ROUTE_RENDER_WAIT);
		server.events.removeAllListeners();
		expect(requests).toHaveLength(1);
		// Membership, survey invitations and the Heph setup are members-only reads of a workspace.
		expect(paths.filter((path) => /^\/workspaces\/[^/]+\//u.test(path))).toStrictEqual([]);
	});

	it("sees the colours of the workspace's provider, not their own", async () => {
		visit();
		server.use(
			http.get("*/public/workspaces/:slug/activity", () =>
				HttpResponse.json(publicActivity({ providerType: "GITLAB" })),
			),
		);
		renderRouteAtWithRouter("/w/acme");

		await screen.findByRole("alertdialog", {}, ROUTE_RENDER_WAIT);
		await waitFor(() => expect(document.documentElement.dataset.provider).toBe("gitlab"));
		screen.getByRole("columnheader", { name: /Merge requests/u, hidden: true });
	});
});

describe("a signed-in person whose consent is pending", () => {
	it("gives it before the page", async () => {
		server.use(
			http.get("*/user/consent", () => HttpResponse.json({ completed: false })),
			http.get("*/workspaces", () => HttpResponse.json([])),
		);
		publish();
		const { router } = renderRouteAtWithRouter("/w/acme");

		await waitFor(() => expect(router.state.location.pathname).toBe("/consent"), ROUTE_RENDER_WAIT);
	});
});

describe("a member", () => {
	it("is asked once, on the first page of a workspace that publishes", async () => {
		const user = userEvent.setup();
		const answers: unknown[] = [];
		let seen = false;
		server.use(
			http.get("*/workspaces", () =>
				HttpResponse.json([workspaceListItem("acme", { publishesPublicActivity: true })]),
			),
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json(ownMembership("MEMBER")),
			),
			http.get("*/user/public-activity/workspaces/acme/onboarding", () =>
				HttpResponse.json({ seen, visible: true }),
			),
			http.put("*/user/public-activity/workspaces/acme/onboarding", async ({ request }) => {
				answers.push(await request.json());
				seen = true;
				return HttpResponse.json({ seen, visible: true });
			}),
		);
		vi.stubGlobal("IntersectionObserver", ObserverStub);
		renderRouteAtWithRouter("/w/acme");

		const step = await screen.findByRole("alertdialog", {}, ROUTE_RENDER_WAIT);
		await user.click(within(step).getByRole("button", { name: "Show me" }));

		await waitFor(() => expect(answers).toStrictEqual([{ visible: true }]));
		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
	});

	it("is not asked where the workspace does not publish", async () => {
		const asked = vi.fn();
		server.use(
			http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])),
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json(ownMembership("MEMBER")),
			),
			http.get("*/user/public-activity/workspaces/acme/onboarding", () => {
				asked();
				return HttpResponse.json({ seen: false, visible: true });
			}),
		);
		vi.stubGlobal("IntersectionObserver", ObserverStub);
		renderRouteAtWithRouter("/w/acme");

		await screen.findByRole("heading", { name: /Activity/u }, ROUTE_RENDER_WAIT);
		expect(asked).not.toHaveBeenCalled();
		expect(screen.queryByRole("alertdialog")).toBeNull();
	});
});

describe("tenant workspace root", () => {
	it("loads the same public activity page and query as the apex workspace path", async () => {
		server.use(unauthenticatedUser);
		const reads: URL[] = [];
		const page = publicActivity();
		publish(page, reads);
		const router = createRouter({
			...ROUTER_SEARCH,
			routeTree,
			origin: "https://acme.hephaestus.build",
			rewrite: workspaceRewrite(
				{ enabled: true, baseDomain: "hephaestus.build", apexOrigin: "https://hephaestus.build" },
				"https://acme.hephaestus.build",
			),
			history: createMemoryHistory({ initialEntries: ["/?range=30d"] }),
			context: { queryClient: new QueryClient(), auth: undefined },
		});
		await router.load();
		expect(router.state.location.pathname).toBe("/w/acme");
		expect(router.history.location.href).toBe("/?range=30d");
		expect(reads).toHaveLength(1);
		expect(reads[0]?.pathname).toBe("/public/workspaces/acme/activity");
		expect(router.state.matches.at(-1)?.loaderData).toMatchObject({
			status: "ready",
			page: { workspaceName: "Acme" },
		});
	});
});
