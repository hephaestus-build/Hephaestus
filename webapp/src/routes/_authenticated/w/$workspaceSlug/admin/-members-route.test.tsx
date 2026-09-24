import { fireEvent, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

vi.setConfig({ testTimeout: 15_000 });

const WORKSPACE = {
	id: 1,
	workspaceSlug: "acme",
	displayName: "Acme",
	providerType: "GITHUB",
	status: "ACTIVE",
	leaguesEnabled: false,
	leaderboardEnabled: false,
	practicesEnabled: true,
	mentorEnabled: false,
	progressionEnabled: false,
};

function mockMembersRoute() {
	server.use(
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
		),
		http.get("*/workspaces", () => HttpResponse.json([WORKSPACE])),
		http.get("*/workspaces/:workspaceSlug", () => HttpResponse.json(WORKSPACE)),
		http.get("*/workspaces/:workspaceSlug/connections/catalog", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/members", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/teams", () => HttpResponse.json([])),
	);
}

describe("workspace members route", () => {
	it("does not trap sidebar navigation", async () => {
		mockMembersRoute();
		const { router } = renderRouteAtWithRouter("/w/acme/admin/members");

		await screen.findByRole("heading", { name: "Members" }, ROUTE_RENDER_WAIT);
		fireEvent.click(await screen.findByRole("link", { name: "Settings" }));

		await waitFor(() => expect(router.state.location.pathname).toBe("/w/acme/admin/settings"));
	});
});
