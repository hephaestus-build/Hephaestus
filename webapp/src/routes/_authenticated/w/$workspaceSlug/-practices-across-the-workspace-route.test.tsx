import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { PracticeStanding, PracticesAcrossWorkspace } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import {
	groupStandings,
	OVERVIEW_FIXTURE,
	packagingGroup,
} from "@/stories/practice-profile-story-mock-data";
import { ACROSS_WORKSPACE } from "@/stories/practices-across-the-workspace-story-data";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

// Mounting the real route pulls in the whole app shell and its lazy modules.
vi.setConfig({ testTimeout: ROUTE_RENDER_WAIT.timeout });

const PAGE = "/w/acme/practices-across-the-workspace";

const FIRST_OPPORTUNITY = "2026-08-01T09:00:00Z";
const LAST_OPPORTUNITY = "2026-09-20T09:00:00Z";

const wire = (window: PracticesAcrossWorkspace["window"]): Wire<PracticesAcrossWorkspace> => ({
	...ACROSS_WORKSPACE,
	window,
	// The trend's support carries its dates as the server sends them, so the transformer is proven.
	groups: ACROSS_WORKSPACE.groups.map(({ yourTrendSupport, ...group }) => ({
		...group,
		...(yourTrendSupport && {
			yourTrendSupport: {
				...yourTrendSupport,
				firstOpportunityAt: FIRST_OPPORTUNITY,
				lastOpportunityAt: LAST_OPPORTUNITY,
			},
		}),
	})),
});

/** The reader's own standing on the practice the level opens. */
const SCOPE_STANDING: PracticeStanding = {
	slug: "scope-to-one-concern",
	name: "Scope the change to one concern",
	groupSlug: "review-ready-work",
	groupName: "Packaging work for review",
	standing: "STRENGTH",
	strengths: [],
	toWorkOn: [],
};

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
		// What the practice level reads of the reader's own profile.
		http.get("*/workspaces/:workspaceSlug/practice-groups", () =>
			HttpResponse.json([packagingGroup]),
		),
		http.get("*/workspaces/:workspaceSlug/practice-groups/standings", () =>
			HttpResponse.json([groupStandings["review-ready-work"]]),
		),
		http.get("*/workspaces/:workspaceSlug/practice-profile/overview", () =>
			HttpResponse.json(OVERVIEW_FIXTURE),
		),
		http.get("*/workspaces/:workspaceSlug/practices/standings", () =>
			HttpResponse.json([SCOPE_STANDING]),
		),
		http.get("*/workspaces/:workspaceSlug/practices/feedback/in-app", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/practice-groups/:groupSlug/review-runs", () =>
			HttpResponse.json({ content: [], hasNext: false, page: 0, size: 10 }),
		),
		http.get("*/workspaces/:workspaceSlug/practices/workspace-overview", ({ request }) => {
			const window = new URL(request.url).searchParams.get("window");
			return HttpResponse.json(wire(window === "ALL_TIME" ? "ALL_TIME" : "DAYS_30"));
		}),
	);
});

afterEach(() => {
	server.events.removeAllListeners();
});

const overviewReads = () =>
	requests.filter((request) => request.url.pathname.endsWith("/practices/workspace-overview"));

/** The reader's own profile reads, which only the reader's own levels need. */
const profileReads = () =>
	requests.filter(({ url }) =>
		["/practice-groups", "/practice-groups/standings", "/practices/standings"].some((path) =>
			url.pathname.endsWith(path),
		),
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
		expect(overviewReads().map((read) => read.url.searchParams.get("window"))).toStrictEqual([
			"DAYS_30",
		]);

		const toolbar = screen.getByRole("toolbar", { name: "Time range" });
		await userEvent.click(within(toolbar).getByRole("button", { name: "All time" }));

		await waitFor(() => {
			expect(overviewReads().map((read) => read.url.searchParams.get("window"))).toStrictEqual([
				"DAYS_30",
				"ALL_TIME",
			]);
		});
		expect(router.state.location.search).toMatchObject({ window: "ALL_TIME" });
	});

	it("opens a group over the page, a practice over the group, and Back closes the practice", async () => {
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
		const level = await screen.findByRole(
			"table",
			{ name: "Practices of Packaging work for review" },
			ROUTE_RENDER_WAIT,
		);
		// The reader's trend in the group, read off the support the wire sent with its dates.
		screen.getByRole("button", { name: "More positive recently" });
		// The level asks for nothing of its own: the page's one read carries every practice's split,
		// and the reader's own profile is read only once one of its levels opens.
		expect(overviewReads()).toHaveLength(1);
		expect(profileReads()).toStrictEqual([]);
		await userEvent.click(
			within(level).getByRole("button", { name: "Open practice Scope the change to one concern" }),
		);

		// The practice opens over its group on this page, the profile's own practice level with the
		// practice's split beside its title.
		await waitFor(() => {
			expect(router.state.location.search).toMatchObject({
				detail: ["practice-group:review-ready-work", "practice:scope-to-one-concern"],
			});
		});
		expect(router.state.location.pathname).toBe(PAGE);
		await screen.findByRole(
			"heading",
			{ name: "Scope the change to one concern" },
			ROUTE_RENDER_WAIT,
		);
		// The path names the page under the levels, then the group, then the practice.
		expect(screen.getAllByRole("button", { name: "Across the workspace" }).length).toBeGreaterThan(
			0,
		);

		// Back closes the practice and leaves its group open.
		router.history.back();
		await waitFor(() => {
			expect(router.state.location.search).toMatchObject({
				detail: ["practice-group:review-ready-work"],
			});
		});
	});

	it("names the span of each standing where a practice sets its own beside the workspace's", async () => {
		// The profile reads the practice over 90 days as Mixed feedback; the window reads it as Needs
		// attention.
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/standings", () =>
				HttpResponse.json([
					{
						...SCOPE_STANDING,
						slug: "keep-the-diff-reviewable",
						name: "Keep the diff reviewable in one sitting",
						standing: "MIXED",
					},
				]),
			),
		);
		renderRouteAtWithRouter(
			`${PAGE}?detail=%5B%22practice-group%3Areview-ready-work%22%2C%22practice%3Akeep-the-diff-reviewable%22%5D`,
		);
		const level = within(
			await screen.findByRole(
				"dialog",
				{ name: "Keep the diff reviewable in one sitting" },
				ROUTE_RENDER_WAIT,
			),
		);
		await level.findByRole("button", { name: "Mixed feedback" }, ROUTE_RENDER_WAIT);
		level.getByText("Last 90 days");
		level.getByText("Last 30 days");
		level.getByRole("img", {
			name: /^28 developers observed in this workspace in the last 30 days: .* You: Needs attention\.$/u,
		});
	});

	it("keeps a practice dismissed after its tab changed, so Back does not open it again", async () => {
		const router = await renderPage();
		await userEvent.click(
			screen.getByRole("button", { name: "Open group Packaging work for review" }),
		);
		const level = await screen.findByRole(
			"table",
			{ name: "Practices of Packaging work for review" },
			ROUTE_RENDER_WAIT,
		);
		await userEvent.click(
			within(level).getByRole("button", { name: "Open practice Scope the change to one concern" }),
		);
		await userEvent.click(
			await screen.findByRole("tab", { name: /About this practice/u }, ROUTE_RENDER_WAIT),
		);
		await waitFor(() => {
			expect(router.state.location.search).toMatchObject({ practiceTab: "about" });
		});
		// A tab is a view of the open practice, so its entry keeps the mark that it was pushed.
		expect(router.state.location.state.detailPush).toBe(true);

		await userEvent.keyboard("{Escape}");
		await waitFor(() => {
			expect(router.state.location.search.detail).toStrictEqual([
				"practice-group:review-ready-work",
			]);
		});
		router.history.back();
		await waitFor(() => {
			expect(router.state.location.search.detail).toBeUndefined();
		});
	});

	it("opens the reader's own group over the group without leaving the page, and Back closes it", async () => {
		const { router } = renderRouteAtWithRouter(
			`${PAGE}?detail=%5B%22practice-group%3Areview-ready-work%22%5D`,
		);
		await userEvent.click(
			await screen.findByRole(
				"button",
				{ name: "Open your group Packaging work for review" },
				ROUTE_RENDER_WAIT,
			),
		);
		await waitFor(() => {
			expect(router.state.location.search).toMatchObject({
				detail: ["practice-group:review-ready-work", "own-group:review-ready-work"],
			});
		});
		expect(router.state.location.pathname).toBe(PAGE);
		// The profile's own group level, with its tabs, over the group across the workspace.
		await screen.findByRole("tab", { name: /About this group/u }, ROUTE_RENDER_WAIT);

		// A practice over it: the path names the group once, then the reader's own profile.
		await userEvent.click(
			await screen.findByRole(
				"button",
				{ name: "Open practice Scope the change to one concern" },
				ROUTE_RENDER_WAIT,
			),
		);
		await waitFor(() => {
			expect(router.state.location.search).toMatchObject({
				detail: [
					"practice-group:review-ready-work",
					"own-group:review-ready-work",
					"practice:scope-to-one-concern",
				],
			});
		});
		await screen.findByRole("button", { name: "Your profile" }, ROUTE_RENDER_WAIT);

		router.history.back();
		await waitFor(() => {
			expect(router.state.location.search).toMatchObject({
				detail: ["practice-group:review-ready-work", "own-group:review-ready-work"],
			});
		});
		router.history.back();
		await waitFor(() => {
			expect(router.state.location.search).toMatchObject({
				detail: ["practice-group:review-ready-work"],
			});
		});
	});

	it("says it could not load the workspace when the read fails", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/workspace-overview", () =>
				HttpResponse.json({ title: "Internal Server Error" }, { status: 500 }),
			),
		);
		renderRouteAtWithRouter(PAGE);

		await screen.findByText("Could not load the workspace", undefined, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("table", { name: "All practice groups" })).toBeNull();
	});
});
