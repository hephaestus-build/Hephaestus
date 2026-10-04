import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { PracticesAcrossWorkspaceTiles } from "@/api/types.gen";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import {
	ACROSS_WORKSPACE,
	ACROSS_WORKSPACE_TILES,
} from "@/stories/practices-across-the-workspace-story-data";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

// Mounting the real route pulls in the whole app shell and its lazy modules.
vi.setConfig({ testTimeout: ROUTE_RENDER_WAIT.timeout });

const PAGE = "/w/acme/practices-across-the-workspace";
const PROFILE = "/w/acme/practice-profile";
const GROUP_OPEN = `${PAGE}?detail=%5B%22practice-group%3Areview-ready-work%22%5D`;

const tilesOf = (
	window: PracticesAcrossWorkspaceTiles["window"],
): PracticesAcrossWorkspaceTiles => ({
	...ACROSS_WORKSPACE_TILES,
	window,
});

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
			HttpResponse.json({ role: "MEMBER", userId: 1, userLogin: "ada", userName: "Ada" }),
		),
		http.get("*/workspaces/:workspaceSlug/practices/workspace-overview", () =>
			HttpResponse.json(ACROSS_WORKSPACE),
		),
		http.get("*/workspaces/:workspaceSlug/practices/workspace-overview/tiles", ({ request }) => {
			const window = new URL(request.url).searchParams.get("window");
			return HttpResponse.json(tilesOf(window === "ALL_TIME" ? "ALL_TIME" : "DAYS_30"));
		}),
	);
});

afterEach(() => {
	server.events.removeAllListeners();
});

const overviewReads = () =>
	requests.filter((request) => request.url.pathname.endsWith("/practices/workspace-overview"));

const tilesReads = () =>
	requests.filter((request) =>
		request.url.pathname.endsWith("/practices/workspace-overview/tiles"),
	);

/** The reader's own profile reads, which this page never makes: the profile makes them. */
const profileReads = () =>
	requests.filter(({ url }) =>
		[
			"/practice-groups",
			"/practice-groups/standings",
			"/practices/standings",
			"/practice-profile/overview",
			"/practices/feedback/in-app",
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
	it("reads the last 30 days by default and each other window as a request of its own", async () => {
		const router = await renderPage();
		await waitFor(() => {
			expect(tilesReads().map((read) => read.url.searchParams.get("window"))).toStrictEqual([
				"DAYS_30",
			]);
		});

		const toolbar = screen.getByRole("toolbar", { name: "Time range" });
		await userEvent.click(within(toolbar).getByRole("button", { name: "All time" }));

		await waitFor(() => {
			expect(tilesReads().map((read) => read.url.searchParams.get("window"))).toStrictEqual([
				"DAYS_30",
				"ALL_TIME",
			]);
		});
		expect(router.state.location.search).toMatchObject({ window: "ALL_TIME" });
		// The bars read no window, so a new window reads them no second time.
		expect(overviewReads()).toHaveLength(1);
	});

	it("opens a group over the page with the group's bar and no standing of the reader's own", async () => {
		const router = await renderPage();
		await userEvent.click(
			screen.getByRole("button", { name: "Open group Packaging work for review" }),
		);

		await waitFor(() => {
			expect(router.state.location.search).toMatchObject({
				detail: ["practice-group:review-ready-work"],
			});
		});
		expect(router.state.location.pathname).toBe(PAGE);
		const level = within(
			await screen.findByRole("dialog", { name: "Packaging work for review" }, ROUTE_RENDER_WAIT),
		);
		await level.findByRole(
			"table",
			{ name: "Practices of Packaging work for review" },
			ROUTE_RENDER_WAIT,
		);
		expect(
			level.getAllByRole("img", {
				name: /^28 developers with a current standing in this workspace: .* The You marker is on Needs attention\.$/u,
			}).length,
		).toBeGreaterThan(0);
		// No standing badge, no trend, no window: the reader's own learning is in their profile.
		expect(level.queryByRole("button", { name: "Needs attention" })).toBeNull();
		expect(level.queryByText(/^Last \d+ days$/u)).toBeNull();
		expect(level.queryByText(/^You:/u)).toBeNull();
		// The level asks for nothing of its own: the page's one read carries every practice's split.
		expect(overviewReads()).toHaveLength(1);
		expect(profileReads()).toStrictEqual([]);

		router.history.back();
		await waitFor(() => {
			expect(router.state.location.search.detail).toBeUndefined();
		});
	});

	it("goes from the group to the same group in the reader's profile", async () => {
		const { router } = renderRouteAtWithRouter(GROUP_OPEN);
		await userEvent.click(
			await screen.findByRole(
				"button",
				{ name: "Open in your Practice profile Packaging work for review" },
				ROUTE_RENDER_WAIT,
			),
		);

		await waitFor(() => {
			expect(router.state.location.pathname).toBe(PROFILE);
		});
		expect(router.state.location.search).toMatchObject({
			detail: ["practice-group:review-ready-work"],
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
			`${PAGE}?detail=%5B%22practice-group%3Areview-ready-work%22%2C%22own-group%3Areview-ready-work%22%2C%22practice%3Ascope-to-one-concern%22%5D`,
		);
		await screen.findByRole("dialog", { name: "Packaging work for review" }, ROUTE_RENDER_WAIT);
		expect(router.state.location.search.detail).toStrictEqual(["practice-group:review-ready-work"]);
		expect(profileReads()).toStrictEqual([]);
	});

	it("says it could not load the workspace when the read fails", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/workspace-overview", () =>
				HttpResponse.json({ title: "Internal Server Error" }, { status: 500 }),
			),
		);
		renderRouteAtWithRouter(PAGE);

		await screen.findByText("We could not load the workspace", undefined, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("table", { name: "All practice groups" })).toBeNull();
	});

	it("keeps the group open when the tiles fail", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/workspace-overview/tiles", () =>
				HttpResponse.json({ title: "Internal Server Error" }, { status: 500 }),
			),
		);
		renderRouteAtWithRouter(GROUP_OPEN);

		await screen.findByText("We could not load the figures", undefined, ROUTE_RENDER_WAIT);
		await screen.findByRole("dialog", { name: "Packaging work for review" }, ROUTE_RENDER_WAIT);
	});
});
