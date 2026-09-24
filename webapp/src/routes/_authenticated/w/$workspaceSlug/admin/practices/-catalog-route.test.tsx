import { screen } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import { mockPracticeDefinitionOptions } from "@/mocks/fixtures/practice";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

vi.setConfig({ testTimeout: 15_000 });

describe("practice catalog route", () => {
	it("keeps the catalog available while new reviews are paused", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
			),
			http.get("*/workspaces", () =>
				HttpResponse.json([
					{
						id: 1,
						workspaceSlug: "acme",
						displayName: "Acme",
						providerType: "GITHUB",
						status: "ACTIVE",
						practicesEnabled: false,
						mentorEnabled: false,
						leaderboardEnabled: false,
						progressionEnabled: false,
						leaguesEnabled: false,
					},
				]),
			),
			http.get("*/workspaces/:workspaceSlug/practice-groups", () => HttpResponse.json([])),
			http.get("*/workspaces/:workspaceSlug/practices", () => HttpResponse.json([])),
			http.get("*/workspaces/:workspaceSlug/practices/definition-options", () =>
				HttpResponse.json(mockPracticeDefinitionOptions),
			),
		);

		renderRouteAt("/w/acme/admin/practices");

		await screen.findByRole("heading", { name: "Practice setup" }, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("button", { name: "Enable practices" })).toBeNull();
	});

	it("restores the work type filter from the URL", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
			),
			http.get("*/workspaces/:workspaceSlug/practice-groups", () => HttpResponse.json([])),
			http.get("*/workspaces/:workspaceSlug/practices", () => HttpResponse.json([])),
			http.get("*/workspaces/:workspaceSlug/practices/definition-options", () =>
				HttpResponse.json(mockPracticeDefinitionOptions),
			),
		);

		renderRouteAt("/w/acme/admin/practices?focus=scm.issue");

		await screen.findByText("Clear the filter to reorder practices.", {}, ROUTE_RENDER_WAIT);
	});
});
