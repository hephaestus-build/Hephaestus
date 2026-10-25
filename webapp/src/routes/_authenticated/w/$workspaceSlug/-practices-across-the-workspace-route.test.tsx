import { focusManager } from "@tanstack/react-query";
import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { stackInSearch } from "@/components/layout/detail-drawer/detail-stack";
import { EARLIER_REVIEWS_COUNTED } from "@/components/practice-vocabulary/standing-basis-defs";
import { FIGURES_LOAD_ERROR } from "@/components/practices-across-the-workspace/across-workspace-copy";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import {
	ACROSS_WORKSPACE,
	EARLIER_REVIEWS_WORKSPACE,
	TILES_BY_WINDOW,
} from "@/stories/practices-across-the-workspace-story-data";
import { deferred, sleep } from "@/test/async";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

// Mounting the real route pulls in the whole app shell and its lazy modules.
vi.setConfig({ testTimeout: ROUTE_RENDER_WAIT.timeout });

const PAGE = "/w/acme/practices-across-the-workspace";
const PROFILE = "/w/acme/practice-profile";
const GROUP_OPEN = `${PAGE}?detail=practice-group:review-ready-work`;
const OVERVIEW = "*/workspaces/:workspaceSlug/practices/workspace-overview";
const TILES = "*/workspaces/:workspaceSlug/practices/tiles";

/** Every request the page made, so a test can count the reads. */
let requests: { url: URL }[] = [];

beforeEach(() => {
	requests = [];
	server.events.on("request:start", ({ request }) => {
		requests.push({ url: new URL(request.url) });
	});
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme", { practicesEnabled: true })]),
		),
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json(ownMembership("MEMBER")),
		),
		http.get(OVERVIEW, () => HttpResponse.json(ACROSS_WORKSPACE)),
		http.get(TILES, ({ request }) => {
			const window = new URL(request.url).searchParams.get("window");
			const tiles = Object.values(TILES_BY_WINDOW).find((each) => each.window === window);
			return tiles === undefined
				? HttpResponse.json({ title: "Bad Request" }, { status: 400 })
				: HttpResponse.json(tiles);
		}),
	);
});

afterEach(() => {
	server.events.removeAllListeners();
});

const overviewReads = () =>
	requests.filter(({ url }) => url.pathname.endsWith("/practices/workspace-overview"));

/** The window of each tiles read, in the order the page asked. */
const tileWindows = () =>
	requests
		.filter(({ url }) => url.pathname.endsWith("/practices/tiles"))
		.map(({ url }) => url.searchParams.get("window"));

/** The reader's own profile reads, which this page never makes: the profile makes them. */
const profileReads = () =>
	requests.filter(({ url }) =>
		[
			"/practice-groups",
			"/practice-groups/standings",
			"/practices/standings",
			"/practice-profile/overview",
			"/practices/feedback/practice-page",
		].some((path) => url.pathname.endsWith(path)),
	);

async function renderPage(path = PAGE) {
	const { router } = renderRouteAtWithRouter(path);
	await screen.findByRole(
		"heading",
		{ level: 1, name: "Practices across the workspace" },
		ROUTE_RENDER_WAIT,
	);
	await screen.findByRole(
		"button",
		{ name: "Open group Packaging work for review" },
		ROUTE_RENDER_WAIT,
	);
	return router;
}

describe("Practices across the workspace", () => {
	it("draws each group's developers once by standing and marks the reader, from one overview read", async () => {
		await renderPage();
		const table = within(screen.getByRole("table", { name: "All practice groups" }));
		table.getByRole("img", {
			name: "28 developers in this workspace: 7 Needs attention, 6 Mixed feedback, 8 Going well, 7 none yet. You are in the Needs attention part.",
		});
		// Parts of one and two show as they are; the reader without a verdict sits in none yet.
		table.getByRole("img", {
			name: "28 developers in this workspace: 1 Needs attention, 0 Mixed feedback, 2 Going well, 25 none yet. You are in the part with no standing yet.",
		});
		expect(overviewReads()).toHaveLength(1);
		expect(profileReads()).toStrictEqual([]);
	});

	it("reads the tiles of the chosen window only, and not the bars again", async () => {
		const router = await renderPage();
		await screen.findByText("11", undefined, ROUTE_RENDER_WAIT);
		expect(tileWindows()).toStrictEqual(["DAYS_30"]);

		const range = screen.getByRole("toolbar", { name: "Time range" });
		await userEvent.click(within(range).getByRole("button", { name: "90 days" }));

		await waitFor(() => {
			expect(router.state.location.search).toMatchObject({ window: "DAYS_90" });
		});
		await screen.findByText("29", undefined, ROUTE_RENDER_WAIT);
		expect(tileWindows()).toStrictEqual(["DAYS_30", "DAYS_90"]);
		expect(overviewReads()).toHaveLength(1);
		screen.getByRole("img", {
			name: "28 developers in this workspace: 7 Needs attention, 6 Mixed feedback, 8 Going well, 7 none yet. You are in the Needs attention part.",
		});
	});

	it("reads the window named in the address", async () => {
		await renderPage(`${PAGE}?window=ALL_TIME`);
		await screen.findByText("64", undefined, ROUTE_RENDER_WAIT);
		expect(tileWindows()).toStrictEqual(["ALL_TIME"]);
	});

	it("opens a group over the page with each practice's split", async () => {
		const router = await renderPage();
		await userEvent.click(
			screen.getByRole("button", { name: "Open group Packaging work for review" }),
		);

		await waitFor(() => {
			expect(router.state.location.search).toMatchObject({
				detail: "practice-group:review-ready-work",
			});
		});
		expect(router.state.location.pathname).toBe(PAGE);
		const level = within(
			await screen.findByRole("dialog", { name: "Packaging work for review" }, ROUTE_RENDER_WAIT),
		);
		const practices = within(
			await level.findByRole(
				"table",
				{ name: "Practices of Packaging work for review" },
				ROUTE_RENDER_WAIT,
			),
		);
		practices.getByRole("img", {
			name: "28 developers in this workspace: 9 Needs attention, 4 Mixed feedback, 8 Going well, 7 none yet. You are in the Needs attention part.",
		});
		expect(overviewReads()).toHaveLength(1);
		expect(profileReads()).toStrictEqual([]);

		router.history.back();
		await waitFor(() => {
			expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([]);
		});
	});

	it("goes from the group to the same group in the reader's profile", async () => {
		const { router } = renderRouteAtWithRouter(GROUP_OPEN);
		await userEvent.click(
			await screen.findByRole(
				"button",
				{ name: "See your own standing: Packaging work for review" },
				ROUTE_RENDER_WAIT,
			),
		);

		await waitFor(() => {
			expect(router.state.location.pathname).toBe(PROFILE);
		});
		expect(router.state.location.search).toMatchObject({
			detail: "practice-group:review-ready-work",
		});
	});

	it("goes from a practice to the same practice in the reader's profile, over its group", async () => {
		const { router } = renderRouteAtWithRouter(GROUP_OPEN);
		await userEvent.click(
			await screen.findByRole(
				"button",
				{ name: "Open in your Practice profile Scope the change to one concern" },
				ROUTE_RENDER_WAIT,
			),
		);

		await waitFor(() => {
			expect(router.state.location.pathname).toBe(PROFILE);
		});
		expect(router.state.location.search).toMatchObject({
			detail: ["practice-group:review-ready-work", "practice:scope-to-one-concern"],
		});
	});

	it("drops a level kind this page does not open", async () => {
		const { router } = renderRouteAtWithRouter(
			`${PAGE}?detail=practice-group:review-ready-work&detail=own-group:review-ready-work&detail=practice:scope-to-one-concern`,
		);
		await screen.findByRole("dialog", { name: "Packaging work for review" }, ROUTE_RENDER_WAIT);
		expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([
			"practice-group:review-ready-work",
		]);
		expect(profileReads()).toStrictEqual([]);
	});

	it("says it could not load the practice groups when the overview fails, in the open level too", async () => {
		server.use(
			http.get(OVERVIEW, () =>
				HttpResponse.json({ title: "Internal Server Error" }, { status: 500 }),
			),
		);
		const { router } = renderRouteAtWithRouter(GROUP_OPEN);

		const level = within(
			await screen.findByRole("dialog", { name: "Practice group" }, ROUTE_RENDER_WAIT),
		);
		await level.findByText("We could not load the practice groups", undefined, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("table", { name: "All practice groups" })).toBeNull();
		// The address still names the group, so the level stays open over the failure.
		expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([
			"practice-group:review-ready-work",
		]);
	});

	it("keeps the bars when only the window's figures fail", async () => {
		server.use(
			http.get(TILES, () => HttpResponse.json({ title: "Internal Server Error" }, { status: 500 })),
		);
		await renderPage();
		await screen.findByText(FIGURES_LOAD_ERROR, undefined, ROUTE_RENDER_WAIT);
		screen.getByRole("table", { name: "All practice groups" });
	});

	it("says once that standings rest on earlier reviews, and marks the reader's own", async () => {
		server.use(http.get(OVERVIEW, () => HttpResponse.json(EARLIER_REVIEWS_WORKSPACE)));
		await renderPage();
		screen.getByText(`${EARLIER_REVIEWS_COUNTED.title}.`);
		const table = within(screen.getByRole("table", { name: "All practice groups" }));
		table.getByRole("button", { name: "Based on earlier reviews, criteria unknown" });
	});

	it("says nothing about earlier reviews when every counted standing is current", async () => {
		await renderPage();
		await screen.findByText("11", undefined, ROUTE_RENDER_WAIT);
		expect(screen.queryByText(`${EARLIER_REVIEWS_COUNTED.title}.`)).toBeNull();
	});

	it("reads the overview once and not again on a refocus", async () => {
		await renderPage();
		act(() => {
			focusManager.setFocused(false);
			focusManager.setFocused(true);
		});
		await sleep(100);

		expect(overviewReads()).toHaveLength(1);
		focusManager.setFocused(undefined);
	});

	it("loads with its skeletons alone, with no counting line, as the Practice profile does", async () => {
		const counted = deferred();
		server.use(
			http.get(OVERVIEW, async () => {
				await counted.promise;
				return HttpResponse.json(ACROSS_WORKSPACE);
			}),
		);
		renderRouteAtWithRouter(PAGE);
		await screen.findByRole(
			"heading",
			{ name: "Practices across the workspace" },
			ROUTE_RENDER_WAIT,
		);
		expect(screen.queryByText(/^Counting /u)).toBeNull();
		expect(screen.queryByRole("button", { name: "About the wait" })).toBeNull();
		counted.resolve();
		await screen.findByRole(
			"button",
			{ name: "Open group Packaging work for review" },
			ROUTE_RENDER_WAIT,
		);
	});
});
