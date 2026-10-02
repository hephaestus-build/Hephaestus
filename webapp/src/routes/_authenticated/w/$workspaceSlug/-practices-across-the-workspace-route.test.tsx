import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { PracticesAcrossWorkspace } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { ACROSS_WORKSPACE } from "@/stories/practices-across-the-workspace-story-data";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

// Mounting the real route pulls in the whole app shell and its lazy modules.
vi.setConfig({ testTimeout: ROUTE_RENDER_WAIT.timeout });

const PAGE = "/w/acme/practices-across-the-workspace";

const wire = (window: PracticesAcrossWorkspace["window"]): Wire<PracticesAcrossWorkspace> => ({
	...ACROSS_WORKSPACE,
	window,
	since: ACROSS_WORKSPACE.since.toISOString(),
	until: ACROSS_WORKSPACE.until.toISOString(),
	// The trend's support carries dates on the wire; no assertion here reads it.
	groups: ACROSS_WORKSPACE.groups.map(({ yourTrendSupport: _support, ...group }) => group),
});

/** Every request the page made, so a test can count the reads. */
let requests: { url: URL }[] = [];

beforeEach(() => {
	requests = [];
	window.localStorage.clear();
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
		http.get("*/workspaces/:workspaceSlug/practices/workspace-overview", ({ request }) => {
			const window = new URL(request.url).searchParams.get("window");
			return HttpResponse.json(wire(window === "DAYS_30" ? "DAYS_30" : "TERM"));
		}),
	);
});

afterEach(() => {
	server.events.removeAllListeners();
	window.localStorage.clear();
});

const overviewReads = () =>
	requests.filter((request) => request.url.pathname.endsWith("/practices/workspace-overview"));

async function renderPage(path = PAGE) {
	const { router } = renderRouteAtWithRouter(path);
	await screen.findByRole(
		"heading",
		{ level: 1, name: "Practices across the workspace" },
		ROUTE_RENDER_WAIT,
	);
	await screen.findByRole(
		"link",
		{ name: "Open group Packaging work for review" },
		ROUTE_RENDER_WAIT,
	);
	return router;
}

describe("Practices across the workspace", () => {
	it("reads the term by default and each other window as a request of its own", async () => {
		const router = await renderPage();
		expect(overviewReads().map((read) => read.url.searchParams.get("window"))).toStrictEqual([
			"TERM",
		]);

		const toolbar = screen.getByRole("toolbar", { name: "Time range" });
		await userEvent.click(within(toolbar).getByRole("button", { name: "30 days" }));

		await waitFor(() => {
			expect(overviewReads().map((read) => read.url.searchParams.get("window"))).toStrictEqual([
				"TERM",
				"DAYS_30",
			]);
		});
		expect(router.state.location.search).toMatchObject({ window: "DAYS_30" });
	});

	it("opens the reader's own group on the practice profile from Open group", async () => {
		const router = await renderPage();
		await userEvent.click(
			screen.getByRole("link", { name: "Open group Packaging work for review" }),
		);

		await waitFor(() => {
			expect(router.state.location.pathname).toBe("/w/acme/practice-profile");
		});
		expect(router.state.location.search).toMatchObject({
			detail: ["practice-group:review-ready-work"],
		});
	});

	it("opens a group's practices over the page and each practice on the practice profile", async () => {
		const router = await renderPage();
		await userEvent.click(
			screen.getByRole("button", { name: "See practices of the group Packaging work for review" }),
		);

		await waitFor(() => {
			expect(router.state.location.search).toMatchObject({
				detail: ["practice-group:review-ready-work"],
			});
		});
		expect(router.state.location.pathname).toBe(PAGE);
		const level = await screen.findByRole(
			"table",
			{ name: "Practices of Packaging work for review" },
			ROUTE_RENDER_WAIT,
		);
		// The level asks for nothing of its own: the page's one read carries every practice's split.
		expect(overviewReads()).toHaveLength(1);
		await userEvent.click(
			within(level).getByRole("link", { name: "View practice Scope the change to one concern" }),
		);

		await waitFor(() => {
			expect(router.state.location.pathname).toBe("/w/acme/practice-profile");
		});
		expect(router.state.location.search).toMatchObject({
			detail: ["practice-group:review-ready-work", "practice:scope-to-one-concern"],
		});
	});

	it("remembers that the reader turned the workspace off", async () => {
		await renderPage();
		await userEvent.click(screen.getByRole("switch", { name: "Show the workspace" }));

		expect(
			window.localStorage.getItem("hephaestus:practices-across-the-workspace:show-workspace"),
		).toBe("off");
		expect(screen.queryAllByRole("img", { name: /developers observed/u })).toStrictEqual([]);
	});
});
