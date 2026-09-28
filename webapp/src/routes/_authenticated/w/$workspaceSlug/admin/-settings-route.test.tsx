import { screen } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt, testQueryClient } from "@/test/router-harness";

// Mounting the real route pulls in the whole admin layout and its lazy modules; the timeout is a
// deadlock backstop, not a budget these renders were meant to fit inside.
vi.setConfig({ testTimeout: 15_000 });

function renderSettingsRoute(practicesEnabled: boolean) {
	server.use(
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
		),
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme", { practicesEnabled })]),
		),
		http.get("*/workspaces/:workspaceSlug/connections/catalog", () => HttpResponse.json([])),
	);
	renderRouteAt("/w/acme/admin/settings", testQueryClient());
}

describe("workspace settings route", () => {
	it("says practice reviews are on from the workspace list and offers no feature switches", async () => {
		renderSettingsRoute(true);

		await screen.findByRole("heading", { name: "Capabilities" }, ROUTE_RENDER_WAIT);
		screen.getByText(/^On\./u);
		expect(screen.queryByRole("switch")).toBeNull();
		expect(screen.getByRole("link", { name: /Review settings/u }).getAttribute("href")).toBe(
			"/w/acme/admin/practices/review",
		);
	});

	it("says practice reviews are off when the workspace has not turned them on", async () => {
		renderSettingsRoute(false);

		await screen.findByText(/^Off\./u, undefined, ROUTE_RENDER_WAIT);
	});
});
