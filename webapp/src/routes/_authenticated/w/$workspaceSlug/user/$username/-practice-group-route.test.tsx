import { act, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
	listPracticeGroupReviewRunsInfiniteQueryKey,
	listWorkspacesQueryKey,
} from "@/api/@tanstack/react-query.gen";
import type { PracticeGroupTrend, ProfileActivityMonitor } from "@/api/types.gen";
import { currentUser } from "@/mocks/fixtures/auth";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

vi.setConfig({ testTimeout: 40_000 });
const path = "/w/acme/user/ada/practice-groups/review-ready-work";
const group = {
	id: 1,
	slug: "review-ready-work",
	name: "Packaging work for review",
	visibleInPracticeDashboards: true,
	displayOrder: 0,
	autonomy: { effective: "AUTOMATIC", inherited: true, source: "WORKSPACE" },
};
const profile = (login: string) => ({
	userInfo: {
		id: 1,
		login,
		name: `Developer ${login}`,
		htmlUrl: `https://github.com/${login}`,
		leaguePoints: 0,
	},
	contributedRepositories: [],
	xpRecord: { currentLevel: 1, currentLevelXP: 0, totalXP: 0, xpNeeded: 150 },
});

const activityMonitor = {
	authoredPullRequests: [],
	repositories: [],
	reviewActivity: [],
	totalAuthoredPullRequestCount: 0,
	totalReviewActivityCount: 0,
	activityStats: {
		numberOfApprovals: 0,
		numberOfChangeRequests: 0,
		numberOfClosedIssues: 0,
		numberOfClosedPullRequests: 0,
		numberOfCodeComments: 0,
		numberOfComments: 0,
		numberOfMergedPullRequests: 0,
		numberOfOpenPullRequests: 0,
		numberOfOpenedIssues: 0,
		numberOfOwnReplies: 0,
		numberOfReviewedPRs: 0,
		numberOfUnknowns: 0,
		score: 0,
	},
} satisfies ProfileActivityMonitor;

let practiceReads = 0;
beforeEach(() => {
	practiceReads = 0;
	server.use(
		http.get("*/user", () => HttpResponse.json({ ...currentUser, username: "ada" })),
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme", { practicesEnabled: true })]),
		),
		http.get("*/workspaces/:workspaceSlug", () => HttpResponse.json(workspaceListItem("acme"))),
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "MEMBER", userId: 1, userLogin: "ada", userName: "Ada" }),
		),
		http.get("*/workspaces/:workspaceSlug/practice-groups", () => {
			practiceReads++;
			return HttpResponse.json([group]);
		}),
		http.get("*/workspaces/:workspaceSlug/practice-groups/standings", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/practices/standings", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/practices/reviewed", () =>
			HttpResponse.json([
				{ slug: "small-changes", name: "Keep changes focused", groupSlug: group.slug },
			]),
		),
		http.get("*/workspaces/:workspaceSlug/practice-groups/:groupSlug/trend", () =>
			HttpResponse.json({
				group: {
					scope: "GROUP",
					slug: group.slug,
					direction: "INSUFFICIENT_EVIDENCE",
					opportunities: [],
					support: {
						bundleSize: 5,
						credibilityThreshold: 0.95,
						currentOpportunities: 0,
						previousOpportunities: 0,
						opportunitiesUntilComparable: 10,
						ropeHalfWidth: 0.1,
					},
				},
				practices: [],
			} satisfies PracticeGroupTrend),
		),
		http.get("*/workspaces/:workspaceSlug/practice-groups/:groupSlug/review-runs", () =>
			HttpResponse.json({ content: [], page: 0, hasNext: false }),
		),
		http.get(
			"*/workspaces/:workspaceSlug/profile/:login",
			() => new HttpResponse(null, { status: 404 }),
		),
		http.get("*/workspaces/:workspaceSlug/profile/:login/activity-monitor", () =>
			HttpResponse.json(activityMonitor),
		),
	);
});
describe("practice-group routes", () => {
	it("redirects a disabled workspace's bookmark without querying practices", async () => {
		server.use(
			http.get("*/workspaces", () =>
				HttpResponse.json([workspaceListItem("acme", { practicesEnabled: false })]),
			),
		);
		const { router } = renderRouteAtWithRouter(path);
		await vi.waitFor(
			() => expect(router.state.location.pathname).toBe("/w/acme/user/ada"),
			ROUTE_RENDER_WAIT,
		);
		expect(practiceReads).toBe(0);
	});
	it("waits for features without redirecting, then loads the enabled surface", async () => {
		let respond = (_response: Response) => {};
		const response = new Promise<Response>((resolve) => {
			respond = resolve;
		});
		server.use(http.get("*/workspaces", () => response.then((value) => value.clone())));
		const { router, queryClient } = renderRouteAtWithRouter(path);
		await vi.waitFor(() =>
			expect(queryClient.getQueryState(listWorkspacesQueryKey())?.fetchStatus).toBe("fetching"),
		);
		expect(router.state.location.pathname).toBe(path);
		expect(practiceReads).toBe(0);
		await act(async () =>
			respond(HttpResponse.json([workspaceListItem("acme", { practicesEnabled: true })])),
		);
		await screen.findByRole("heading", { name: group.name }, ROUTE_RENDER_WAIT);
		expect(practiceReads).toBeGreaterThan(0);
	});
	it("keeps a feature failure recoverable instead of redirecting", async () => {
		server.use(http.get("*/workspaces", () => new HttpResponse(null, { status: 500 })));
		const { router } = renderRouteAtWithRouter(path);
		await vi.waitFor(() => expect(router.state.isLoading).toBe(false), ROUTE_RENDER_WAIT);
		await screen.findByRole("button", { name: "Retry" }, ROUTE_RENDER_WAIT);
		expect(router.state.location.pathname).toBe(path);
		expect(practiceReads).toBe(0);
		server.use(
			http.get("*/workspaces", () =>
				HttpResponse.json([workspaceListItem("acme", { practicesEnabled: true })]),
			),
		);
		await userEvent.click(screen.getByRole("button", { name: /retry/i }));
		await screen.findByRole("heading", { name: group.name }, ROUTE_RENDER_WAIT);
	});
	it("filters and clears review runs without resetting scroll", async () => {
		const { router } = renderRouteAtWithRouter(path);
		const main = await screen.findByRole("main");
		const filter = await within(main).findByRole(
			"button",
			{
				name: "Show review runs for Keep changes focused",
			},
			ROUTE_RENDER_WAIT,
		);
		const scroll = vi.spyOn(window, "scrollTo").mockReturnValue(undefined);
		await userEvent.click(filter);
		await vi.waitFor(() =>
			expect(router.state.location.search).toMatchObject({ practice: "small-changes" }),
		);
		await userEvent.click(
			screen.getByRole("button", { name: "Clear review-run filter for Keep changes focused" }),
		);
		await vi.waitFor(() => expect(router.state.location.search).not.toHaveProperty("practice"));
		expect(scroll).not.toHaveBeenCalled();
		scroll.mockRestore();
	});
});

it("does not label another developer's profile with the previous developer's data", async () => {
	let respond = (_response: Response) => {};
	const pendingProfile = new Promise<Response>((resolve) => {
		respond = resolve;
	});
	server.use(
		http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])),
		http.get("*/workspaces/:workspaceSlug/profile/ada", () => HttpResponse.json(profile("ada"))),
		http.get("*/workspaces/:workspaceSlug/profile/bob", () =>
			pendingProfile.then((value) => value.clone()),
		),
	);
	const { router } = renderRouteAtWithRouter("/w/acme/user/ada");
	await screen.findByRole("heading", { name: "Developer ada" }, ROUTE_RENDER_WAIT);
	await act(() =>
		router.navigate({
			to: "/w/$workspaceSlug/user/$username",
			params: { workspaceSlug: "acme", username: "bob" },
		}),
	);
	expect(screen.queryByRole("heading", { name: "Developer ada" })).toBeNull();
	await act(async () => respond(HttpResponse.json(profile("bob"))));
	await screen.findByRole("heading", { name: "Developer bob" }, ROUTE_RENDER_WAIT);
});

it("refreshes every cached filter of the group after responding to feedback", async () => {
	const observationId = "00000000-0000-0000-0000-000000000001";
	let usefulness: "HELPFUL" | undefined;
	server.use(
		http.get("*/workspaces/:workspaceSlug/practice-groups/:groupSlug/review-runs", () =>
			HttpResponse.json({
				content: [
					{
						reviewId: "00000000-0000-0000-0000-000000000003",
						reviewedAt: "2026-09-01T10:00:00Z",
						reviewedWork: { id: 1, type: "scm.pull_request", title: "A focused change" },
						observations: [
							{
								observationId,
								feedbackId: "00000000-0000-0000-0000-000000000002",
								practiceSlug: "small-changes",
								practiceName: "Keep changes focused",
								title: "Two concerns in one change",
								presence: "PRESENT",
								assessment: "BAD",
								feedbackUsefulness: usefulness,
							},
						],
					},
				],
				hasNext: false,
				page: 0,
			}),
		),
		http.get("*/workspaces/:workspaceSlug/practices/observations/:observationId", () =>
			HttpResponse.json({ observedAt: "2026-09-01T10:00:00Z" }),
		),
		http.put("*/workspaces/:workspaceSlug/practices/feedback/:feedbackId/response", () => {
			usefulness = "HELPFUL";
			return HttpResponse.json({ usefulness: "HELPFUL" });
		}),
	);
	const { queryClient } = renderRouteAtWithRouter(`${path}?observation=${observationId}`);
	const main = await screen.findByRole("main");
	const helpful = await within(main).findByRole("button", { name: "Helpful" }, ROUTE_RENDER_WAIT);
	const filtered = listPracticeGroupReviewRunsInfiniteQueryKey({
		path: { workspaceSlug: "acme", groupSlug: group.slug },
		query: { size: 10, practiceSlug: "small-changes" },
	});
	queryClient.setQueryData(filtered, { pages: [{ content: [], hasNext: false }], pageParams: [0] });
	await userEvent.click(helpful);
	await vi.waitFor(() => expect(helpful.getAttribute("aria-pressed")).toBe("true"));
	expect(queryClient.getQueryState(filtered)?.isInvalidated).toBe(true);
});

it("restores the bookmarked custom timeframe on Back without scrolling on selection", async () => {
	const after = "2026-06-02T00:00:00Z";
	const before = "2026-06-07T00:00:00Z";
	server.use(
		http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])),
		http.get("*/workspaces/:workspaceSlug/profile/ada", () => HttpResponse.json(profile("ada"))),
	);
	const { router } = renderRouteAtWithRouter(`/w/acme/user/ada?after=${after}&before=${before}`);
	await screen.findByRole("heading", { name: "Developer ada" }, ROUTE_RENDER_WAIT);
	const scroll = vi.spyOn(window, "scrollTo").mockReturnValue(undefined);
	await userEvent.click(screen.getByRole("combobox", { name: "Timeframe" }));
	await userEvent.click(await screen.findByRole("option", { name: "Last week" }));
	await vi.waitFor(() => expect(router.state.location.search.after).not.toBe(after));
	expect(scroll).not.toHaveBeenCalled();
	scroll.mockRestore();
	act(() => router.history.back());
	await vi.waitFor(() => expect(router.state.location.search).toMatchObject({ after, before }));
	await vi.waitFor(() =>
		expect(screen.getByRole("combobox", { name: "Timeframe" }).textContent).toContain(
			"Custom range",
		),
	);
	expect(screen.getByRole("button", { name: "Choose custom dates" }).textContent).toContain(
		"Jun 2 – 6",
	);
	act(() => router.history.forward());
	await vi.waitFor(() =>
		expect(screen.getByRole("combobox", { name: "Timeframe" }).textContent).toContain("Last week"),
	);
	expect(screen.queryByRole("button", { name: "Choose custom dates" })).toBeNull();
});

it.each([
	{
		subject: "workspace",
		endpoint: "*/workspaces/:workspaceSlug",
		response: workspaceListItem("acme"),
	},
	{
		subject: "activity",
		endpoint: "*/workspaces/:workspaceSlug/profile/:login/activity-monitor",
		response: activityMonitor,
	},
])(
	"retries a failed $subject query without discarding the loaded profile",
	async ({ endpoint, response }) => {
		server.use(
			http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])),
			http.get("*/workspaces/:workspaceSlug/profile/ada", () => HttpResponse.json(profile("ada"))),
			http.get(endpoint, () => HttpResponse.json({ status: 503 }, { status: 503 })),
		);
		renderRouteAtWithRouter("/w/acme/user/ada");
		await screen.findByText("Could not load activity", {}, ROUTE_RENDER_WAIT);
		screen.getByRole("heading", { name: "Developer ada" });
		server.use(http.get(endpoint, () => HttpResponse.json(response)));
		await userEvent.click(screen.getByRole("button", { name: "Retry" }));
		await vi.waitFor(() => expect(screen.queryByText("Could not load activity")).toBeNull());
		screen.getByRole("combobox", { name: "Timeframe" });
		screen.getByRole("heading", { name: "Developer ada" });
	},
);

it("does not present the previous timeframe's activity as the newly selected range", async () => {
	let respond = (_response: Response) => {};
	const pendingActivity = new Promise<Response>((resolve) => {
		respond = resolve;
	});
	let activityReads = 0;
	server.use(
		http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])),
		http.get("*/workspaces/:workspaceSlug/profile/ada", () => HttpResponse.json(profile("ada"))),
		http.get("*/workspaces/:workspaceSlug/profile/:login/activity-monitor", () =>
			HttpResponse.json(activityMonitor),
		),
	);
	renderRouteAtWithRouter("/w/acme/user/ada?after=2026-06-02T00:00:00Z");
	await screen.findByRole("heading", { name: "No review activity" }, ROUTE_RENDER_WAIT);
	server.use(
		http.get("*/workspaces/:workspaceSlug/profile/:login/activity-monitor", () => {
			activityReads++;
			return pendingActivity.then((response) => response.clone());
		}),
	);
	await userEvent.click(screen.getByRole("combobox", { name: "Timeframe" }));
	await userEvent.click(await screen.findByRole("option", { name: "Last week" }));
	await vi.waitFor(() => expect(activityReads).toBe(1));
	expect(screen.queryByRole("heading", { name: "No review activity" })).toBeNull();
	screen.getByRole("heading", { name: "Developer ada" });
	screen.getByRole("combobox", { name: "Timeframe" });
	await act(async () => respond(HttpResponse.json(activityMonitor)));
	await screen.findByRole("heading", { name: "No review activity" }, ROUTE_RENDER_WAIT);
});
