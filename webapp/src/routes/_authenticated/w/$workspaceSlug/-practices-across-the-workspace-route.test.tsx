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
});

/** Every request the page made, method and address, so a test can say what never left the browser. */
let requests: { method: string; url: URL; body: string }[] = [];

beforeEach(() => {
	requests = [];
	window.localStorage.clear();
	server.events.on("request:start", ({ request }) => {
		void request
			.clone()
			.text()
			.then((body) => {
				requests.push({ method: request.method, url: new URL(request.url), body });
			});
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
	await screen.findByRole("list", { name: "All practice groups" }, ROUTE_RENDER_WAIT);
	return router;
}

describe("Practices across the workspace", () => {
	it("reads the term by default and each other window as a request of its own", async () => {
		const router = await renderPage();
		expect(overviewReads().map((read) => read.url.searchParams.get("window"))).toEqual(["TERM"]);

		const toolbar = screen.getByRole("toolbar", { name: "Time range" });
		await userEvent.click(within(toolbar).getByRole("button", { name: "30 days" }));

		await waitFor(() => {
			expect(overviewReads().map((read) => read.url.searchParams.get("window"))).toEqual([
				"TERM",
				"DAYS_30",
			]);
		});
		expect(router.state.location.search).toEqual({ window: "DAYS_30" });
	});

	it("keeps an estimate in this browser and never sends it", async () => {
		await renderPage();
		const question = screen.getByRole("group", {
			name: "Your estimate for Packaging work for review",
		});
		await userEvent.click(within(question).getByRole("button", { name: "Going well" }));

		await screen.findByText(
			"You expected Going well; your latest reviewed work reads Needs attention.",
		);
		expect(
			window.localStorage.getItem(
				"hephaestus:practices-across-the-workspace:estimate:acme:review-ready-work",
			),
		).toBe("STRENGTH");
		// Nothing the page asked for carries the estimate: every request is a read without a body.
		for (const request of requests) {
			expect(request.method).toBe("GET");
			expect(request.body).toBe("");
			expect(request.url.search).not.toContain("STRENGTH");
		}
	});

	it("opens the reader's own group on the practice profile from the group's name", async () => {
		const router = await renderPage();
		await userEvent.click(
			screen.getByRole("link", {
				name: "Packaging work for review, open your own group on your Practice profile",
			}),
		);

		await waitFor(() => {
			expect(router.state.location.pathname).toBe("/w/acme/practice-profile");
		});
		expect(router.state.location.search).toMatchObject({
			detail: ["practice-group:review-ready-work"],
		});
	});

	it("remembers that the reader turned the workspace off", async () => {
		await renderPage();
		await userEvent.click(screen.getByRole("switch", { name: "Show the workspace" }));

		expect(
			window.localStorage.getItem("hephaestus:practices-across-the-workspace:show-workspace"),
		).toBe("off");
		expect(screen.queryAllByRole("img", { name: /developers observed/u })).toEqual([]);
	});
});
