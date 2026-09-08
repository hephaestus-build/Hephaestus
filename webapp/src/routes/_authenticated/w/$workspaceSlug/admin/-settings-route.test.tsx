import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import {
	computeUserLeagueStatsQueryKey,
	getLeaderboardQueryKey,
} from "@/api/@tanstack/react-query.gen";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt, testQueryClient } from "@/test/router-harness";

// Mounting the real route pulls in the whole admin layout and its lazy modules; the timeout is a
// deadlock backstop, not a budget these renders were meant to fit inside.
vi.setConfig({ testTimeout: 15_000 });

const WORKSPACE = {
	id: 1,
	workspaceSlug: "acme",
	displayName: "Acme",
	providerType: "GITHUB",
	status: "ACTIVE",
	leaguesEnabled: true,
	leaderboardEnabled: true,
	practicesEnabled: false,
	mentorEnabled: false,
	progressionEnabled: false,
	practiceReviewAutoTriggerEnabled: true,
	practiceReviewManualTriggerEnabled: true,
};

const LEADERBOARD_KEY = getLeaderboardQueryKey({
	path: { workspaceSlug: "acme" },
	query: {
		after: new Date("2026-07-01T00:00:00.000Z"),
		before: new Date("2026-07-31T00:00:00.000Z"),
		team: "all",
		sort: "LEAGUE_POINTS",
		mode: "INDIVIDUAL",
	},
});

const LEAGUE_STATS_KEY = computeUserLeagueStatsQueryKey({
	path: { workspaceSlug: "acme", login: "ada" },
	query: {
		after: new Date("2026-07-01T00:00:00.000Z"),
		before: new Date("2026-07-31T00:00:00.000Z"),
	},
});

async function renderSettingsRoute() {
	let resetCalls = 0;
	server.use(
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
		),
		http.get("*/workspaces", () => HttpResponse.json([WORKSPACE])),
		http.get("*/workspaces/:workspaceSlug", () => HttpResponse.json(WORKSPACE)),
		http.get("*/workspaces/:workspaceSlug/connections/catalog", () => HttpResponse.json([])),
		http.put("*/workspaces/:workspaceSlug/league/reset", () => {
			resetCalls += 1;
			return new HttpResponse(null, { status: 204 });
		}),
	);

	const queryClient = testQueryClient();
	for (const queryKey of [LEADERBOARD_KEY, LEAGUE_STATS_KEY]) {
		queryClient.setQueryData(queryKey, []);
		queryClient.setQueryDefaults(queryKey, { staleTime: Number.POSITIVE_INFINITY });
	}

	renderRouteAt("/w/acme/admin/settings", queryClient);
	await screen.findByRole("button", { name: "Reset and Recalculate Leagues" }, ROUTE_RENDER_WAIT);
	return { queryClient, resetCalls: () => resetCalls };
}

describe("workspace settings route", () => {
	it("marks the leaderboard and league stats stale after a reset", async () => {
		const { queryClient, resetCalls } = await renderSettingsRoute();

		expect(queryClient.getQueryState(LEADERBOARD_KEY)?.isInvalidated).toBe(false);

		fireEvent.click(screen.getByRole("button", { name: "Reset and Recalculate Leagues" }));
		const dialog = await screen.findByRole("alertdialog");
		fireEvent.click(await within(dialog).findByRole("button", { name: "Reset and Recalculate" }));

		await waitFor(() => expect(resetCalls()).toBe(1));
		await waitFor(() =>
			expect(queryClient.getQueryState(LEADERBOARD_KEY)?.isInvalidated).toBe(true),
		);
		expect(queryClient.getQueryState(LEAGUE_STATS_KEY)?.isInvalidated).toBe(true);
	});
});
