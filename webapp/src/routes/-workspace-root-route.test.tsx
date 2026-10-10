import { QueryClient } from "@tanstack/react-query";
import { createMemoryHistory, createRouter } from "@tanstack/react-router";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import type { PublicActivity } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { ROUTER_SEARCH } from "@/lib/router-search";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { unauthenticatedUser } from "@/mocks/handlers";
import { server } from "@/mocks/server";
import { routeTree } from "@/routeTree.gen";
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
		expect(router.state.location.search.returnTo).toBe("/w/private/activity?range=1y");
	});

	it("shows the page to a visitor when the identity cannot be read", async () => {
		server.use(http.get("*/user", () => new HttpResponse(null, { status: 503 })));
		publish();
		renderRouteAtWithRouter("/w/acme");

		await screen.findByRole("heading", { name: "Acme activity" }, ROUTE_RENDER_WAIT);
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

		await user.click(screen.getByRole("button", { name: "Contributions" }));
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
		await user.click(screen.getByRole("button", { name: /Retry/u }));
		await screen.findByRole("heading", { name: "Acme activity" });
		expect(names()).toHaveLength(2);
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

describe("a signed-in person who is not a member", () => {
	it("sees the page, and the step to show or hide themselves", async () => {
		const user = userEvent.setup();
		const answers: unknown[] = [];
		let seen = false;
		publish();
		server.use(
			http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("other")])),
			http.get("*/user/public-activity/workspaces/acme/onboarding", () =>
				HttpResponse.json({ seen, visible: true }),
			),
			http.put("*/user/public-activity/workspaces/acme/onboarding", async ({ request }) => {
				answers.push(await request.json());
				seen = true;
				return HttpResponse.json({ seen, visible: false });
			}),
		);
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
				HttpResponse.json({ role: "MEMBER", userId: 42, userLogin: "ada", userName: "Ada" }),
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
				HttpResponse.json({ role: "MEMBER", userId: 42, userLogin: "ada", userName: "Ada" }),
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
