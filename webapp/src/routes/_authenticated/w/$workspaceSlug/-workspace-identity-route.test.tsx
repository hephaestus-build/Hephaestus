import { screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { ProfileActivityMonitor } from "@/api/types.gen";
import { currentUser } from "@/mocks/fixtures/auth";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

// A case mounts the whole app chrome, whose route modules are imported lazily.
vi.setConfig({ testTimeout: 40_000 });

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

/** Signed in as `ada`, while the workspace's connected instance knows the same account as `ada-lrz`. */
describe("the account's own pages in a workspace", () => {
	let profileLogins: string[];
	let standingsReads: number;

	beforeEach(() => {
		profileLogins = [];
		standingsReads = 0;
		server.use(
			http.get("*/user", () => HttpResponse.json({ ...currentUser, username: "ada" })),
			http.get("*/workspaces", () =>
				HttpResponse.json([workspaceListItem("acme", { practicesEnabled: true })]),
			),
			http.get("*/workspaces/:workspaceSlug", () => HttpResponse.json(workspaceListItem("acme"))),
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json({ role: "MEMBER", userId: 7, userLogin: "ada-lrz", userName: "Ada" }),
			),
			http.get("*/workspaces/:workspaceSlug/profile/:login", ({ params }) => {
				profileLogins.push(String(params.login));
				return new HttpResponse(null, { status: 404 });
			}),
			http.get("*/workspaces/:workspaceSlug/profile/:login/activity-monitor", () =>
				HttpResponse.json(activityMonitor),
			),
			http.get("*/workspaces/:workspaceSlug/practice-groups", () => HttpResponse.json([])),
			http.get("*/workspaces/:workspaceSlug/practice-groups/standings", () => {
				standingsReads += 1;
				return HttpResponse.json([]);
			}),
			http.get("*/workspaces/:workspaceSlug/practices/standings", () => HttpResponse.json([])),
		);
	});

	it("opens the workspace identity's profile from a home without a leaderboard", async () => {
		const { router } = renderRouteAtWithRouter("/w/acme");

		await waitFor(
			() => expect(router.state.location.pathname).toBe("/w/acme/user/ada-lrz"),
			ROUTE_RENDER_WAIT,
		);
		expect(profileLogins).not.toContain("ada");
	});

	it("treats the workspace identity's profile as the account's own and links to it", async () => {
		renderRouteAtWithRouter("/w/acme/user/ada-lrz");

		const profile = await screen.findByRole("link", { name: "Profile" }, ROUTE_RENDER_WAIT);
		expect(profile.getAttribute("href")).toMatch(/^\/w\/acme\/user\/ada-lrz(?:\?|$)/u);
		await waitFor(() => expect(standingsReads).toBeGreaterThan(0));
	});

	it("opens a practice group page on the workspace identity's profile", async () => {
		const path = "/w/acme/user/ada-lrz/practice-groups/review-ready-work";
		const { router } = renderRouteAtWithRouter(path);

		await waitFor(
			() => expect(router.state.resolvedLocation?.pathname).toBe(path),
			ROUTE_RENDER_WAIT,
		);
	});

	it("sends the sign-in login's practice group page back to that profile", async () => {
		const { router } = renderRouteAtWithRouter(
			"/w/acme/user/ada/practice-groups/review-ready-work",
		);

		await waitFor(
			() => expect(router.state.resolvedLocation?.pathname).toBe("/w/acme/user/ada"),
			ROUTE_RENDER_WAIT,
		);
	});
});
