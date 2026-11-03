import { QueryClient } from "@tanstack/react-query";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { stackInSearch } from "@/components/layout/detail-drawer/detail-stack";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import {
	groups,
	groupStandings,
	OVERVIEW_FIXTURE,
	packagingGroup,
	practiceStandings,
} from "@/stories/practice-profile-story-mock-data";
import {
	ACROSS_WORKSPACE,
	ACROSS_WORKSPACE_TILES,
} from "@/stories/practices-across-the-workspace-story-data";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

// Mounting the real routes pulls in the whole app shell and its lazy modules.
// Twenty switches mount a route each, so the loop case gets the time of several.
vi.setConfig({ testTimeout: ROUTE_RENDER_WAIT.timeout * 4 });

const WORKSPACE_PAGE = "/w/acme/practices-across-the-workspace";
const PROFILE = "/w/acme/practice-profile";
const GROUP = `practice-group:${packagingGroup.slug}`;
const TO_PROFILE = `See your own standing: ${packagingGroup.name}`;
const ALL_GROUPS = "practice-groups:all";
const TO_OWN_GROUPS = "See your own standing";
const TO_ALL_GROUPS = "See all groups across the workspace";
const TO_WORKSPACE = `See this group across the workspace: ${packagingGroup.name}`;
const [practice] = practiceStandings;
if (!practice) {
	throw new Error("The fixtures carry a practice with a standing");
}

/** Twenty switches: ten times to the profile and ten times back. */
const ROUND_TRIPS = 10;

let requests: URL[] = [];

beforeEach(() => {
	requests = [];
	server.events.on("request:start", ({ request }) => {
		requests.push(new URL(request.url));
	});
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme", { practicesEnabled: true })]),
		),
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json(ownMembership("MEMBER")),
		),
		http.get("*/workspaces/:workspaceSlug/practices/workspace-overview", () =>
			HttpResponse.json(ACROSS_WORKSPACE),
		),
		http.get("*/workspaces/:workspaceSlug/practices/tiles", () =>
			HttpResponse.json(ACROSS_WORKSPACE_TILES),
		),
		http.get("*/workspaces/:workspaceSlug/practice-groups", () => HttpResponse.json(groups)),
		http.get("*/workspaces/:workspaceSlug/practices/reviewed", () =>
			HttpResponse.json(
				practiceStandings.map((standing) => ({
					slug: standing.slug,
					name: standing.name,
					group: groups.find((group) => group.slug === standing.groupSlug) ?? null,
					whyItMatters: null,
					whatGoodLooksLike: null,
				})),
			),
		),
		http.get("*/workspaces/:workspaceSlug/practice-groups/standings", () =>
			HttpResponse.json(Object.values(groupStandings)),
		),
		http.get("*/workspaces/:workspaceSlug/practices/standings", () =>
			HttpResponse.json(practiceStandings),
		),
		http.get("*/workspaces/:workspaceSlug/practice-profile/overview", () =>
			HttpResponse.json(OVERVIEW_FIXTURE),
		),
		http.get("*/workspaces/:workspaceSlug/practices/feedback/practice-page", () =>
			HttpResponse.json([]),
		),
		http.get("*/workspaces/:workspaceSlug/practice-groups/:groupSlug/reviews", () =>
			HttpResponse.json({
				content: [],
				page: 0,
				size: 10,
				totalElements: 0,
				totalPages: 0,
			}),
		),
	);
});

afterEach(() => {
	server.events.removeAllListeners();
});

/**
 * Data that never goes stale, so any read after the first comes from the switch itself and not from
 * the clock: a slow run would otherwise pass the app's 30 seconds and read again, as it should.
 */
function appQueryClient() {
	return new QueryClient({
		defaultOptions: { queries: { retry: false, staleTime: Number.POSITIVE_INFINITY } },
	});
}

const readsOf = (path: string) => requests.filter((url) => url.pathname.endsWith(path)).length;

/** The workspace page with the group open over it, reached the way a reader reaches it. */
async function openWorkspaceGroup() {
	const { router } = renderRouteAtWithRouter(WORKSPACE_PAGE, appQueryClient());
	await userEvent.click(
		await screen.findByRole(
			"button",
			{ name: `Open group ${packagingGroup.name}` },
			ROUTE_RENDER_WAIT,
		),
	);
	await screen.findByRole("dialog", { name: packagingGroup.name }, ROUTE_RENDER_WAIT);
	return router;
}

async function switchTo(
	name: string,
	pathname: string,
	router: { state: { location: { pathname: string } } },
) {
	await userEvent.click(await screen.findByRole("button", { name }, ROUTE_RENDER_WAIT));
	await waitFor(() => {
		expect(router.state.location.pathname).toBe(pathname);
	}, ROUTE_RENDER_WAIT);
	await screen.findByRole("dialog", { name: packagingGroup.name }, ROUTE_RENDER_WAIT);
}

describe("going between the Practice profile and Practices across the workspace", () => {
	it("keeps history, the level stack and the reads bounded however often the reader switches", async () => {
		const router = await openWorkspaceGroup();
		const origin = router.history.length;

		for (let trip = 0; trip < ROUND_TRIPS; trip += 1) {
			await switchTo(TO_PROFILE, PROFILE, router);
			expect(router.state.location.search).toMatchObject({ detail: GROUP });
			await switchTo(TO_WORKSPACE, WORKSPACE_PAGE, router);
			expect(router.state.location.search).toMatchObject({ detail: GROUP });
			// One step for the first switch, none for every switch after it.
			expect(router.history).toHaveLength(origin + 1);
			expect(screen.getAllByRole("dialog")).toHaveLength(1);
		}

		// Each page read its data once; coming back asks for nothing again.
		expect(readsOf("/practices/workspace-overview")).toBe(1);
		expect(readsOf("/practices/feedback/practice-page")).toBe(1);
		expect(readsOf("/practice-profile/overview")).toBe(1);

		// Back leaves the pair in one step, to where the reader stood before the first switch.
		router.history.back();
		await waitFor(() => {
			expect(router.state.location.pathname).toBe(WORKSPACE_PAGE);
		});
		expect(router.state.location.state.focusSwitch).toBeUndefined();
		expect(router.state.location.search).toMatchObject({ detail: GROUP });
	});

	it("keeps history bounded between the list of every group and the workspace page", async () => {
		const { router } = renderRouteAtWithRouter(WORKSPACE_PAGE, appQueryClient());
		await screen.findByRole("button", { name: TO_OWN_GROUPS }, ROUTE_RENDER_WAIT);
		const origin = router.history.length;

		for (let trip = 0; trip < ROUND_TRIPS; trip += 1) {
			await userEvent.click(await screen.findByRole("button", { name: TO_OWN_GROUPS }));
			await waitFor(() => {
				expect(router.state.location.pathname).toBe(PROFILE);
			}, ROUTE_RENDER_WAIT);
			expect(router.state.location.search).toMatchObject({ detail: ALL_GROUPS });
			await screen.findByRole("dialog", { name: "All practice groups" }, ROUTE_RENDER_WAIT);

			await userEvent.click(
				await screen.findByRole("button", { name: TO_ALL_GROUPS }, ROUTE_RENDER_WAIT),
			);
			await waitFor(() => {
				expect(router.state.location.pathname).toBe(WORKSPACE_PAGE);
			}, ROUTE_RENDER_WAIT);
			expect(router.state.location.search.detail).toBeUndefined();
			// One step for the first switch, none for every switch after it, and no level open.
			expect(router.history).toHaveLength(origin + 1);
			expect(screen.queryAllByRole("dialog")).toHaveLength(0);
		}

		router.history.back();
		await waitFor(() => {
			expect(router.state.location.pathname).toBe(WORKSPACE_PAGE);
		});
		expect(router.state.location.state.focusSwitch).toBeUndefined();
	});

	it("closes a level the switch opened without opening it again on Back", async () => {
		const router = await openWorkspaceGroup();
		await switchTo(TO_PROFILE, PROFILE, router);
		const afterSwitch = router.history.length;

		await userEvent.click(
			within(screen.getByRole("dialog", { name: packagingGroup.name })).getByRole("button", {
				name: "Close",
			}),
		);
		await waitFor(() => {
			expect(router.state.location.search.detail).toBeUndefined();
		}, ROUTE_RENDER_WAIT);
		// The close rewrote the switch's step instead of adding one.
		expect(router.history).toHaveLength(afterSwitch);

		router.history.back();
		await waitFor(() => {
			expect(router.state.location.pathname).toBe(WORKSPACE_PAGE);
		});
	});

	it("keeps a level the reader opened after a switch as a step of its own", async () => {
		const router = await openWorkspaceGroup();
		await switchTo(TO_PROFILE, PROFILE, router);
		const afterSwitch = router.history.length;

		// The reader opens a practice over the group the switch opened: a new step, without the mark.
		await userEvent.click(
			await screen.findByRole(
				"button",
				{ name: `Open practice ${practice.name}` },
				ROUTE_RENDER_WAIT,
			),
		);
		await waitFor(() => {
			expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([
				GROUP,
				`practice:${practice.slug}`,
			]);
		}, ROUTE_RENDER_WAIT);
		expect(router.history).toHaveLength(afterSwitch + 1);
		expect(router.state.location.state.focusSwitch).toBeUndefined();

		// The next switch adds a step instead of replacing the level the reader opened.
		await userEvent.click(
			await screen.findByRole(
				"button",
				{ name: `See this practice across the workspace: ${practice.name}` },
				ROUTE_RENDER_WAIT,
			),
		);
		await waitFor(() => {
			expect(router.state.location.pathname).toBe(WORKSPACE_PAGE);
		}, ROUTE_RENDER_WAIT);
		expect(router.history).toHaveLength(afterSwitch + 2);

		// Back returns to that level, not past it.
		router.history.back();
		await waitFor(() => {
			expect(router.state.location.pathname).toBe(PROFILE);
		}, ROUTE_RENDER_WAIT);
		expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([
			GROUP,
			`practice:${practice.slug}`,
		]);
	});

	it("carries the practice both ways and marks it in its group across the workspace", async () => {
		const { router } = renderRouteAtWithRouter(
			`${PROFILE}?detail=${encodeURIComponent(JSON.stringify([GROUP, `practice:${practice.slug}`]))}`,
			appQueryClient(),
		);
		await userEvent.click(
			await screen.findByRole(
				"button",
				{ name: `See this practice across the workspace: ${practice.name}` },
				ROUTE_RENDER_WAIT,
			),
		);
		await waitFor(() => {
			expect(router.state.location.pathname).toBe(WORKSPACE_PAGE);
		}, ROUTE_RENDER_WAIT);
		expect(router.state.location.search).toMatchObject({
			detail: GROUP,
			practice: practice.slug,
		});

		// A practice row goes to that practice over its group, never onto the levels already open.
		await userEvent.click(
			await screen.findByRole(
				"button",
				{ name: "Open in your Practice profile Scope the change to one concern" },
				ROUTE_RENDER_WAIT,
			),
		);
		await waitFor(() => {
			expect(router.state.location.pathname).toBe(PROFILE);
		}, ROUTE_RENDER_WAIT);
		expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([
			GROUP,
			"practice:scope-to-one-concern",
		]);
	});
});
