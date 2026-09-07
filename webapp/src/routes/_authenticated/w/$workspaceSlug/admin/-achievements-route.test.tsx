import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });

const workspace = {
	id: 1,
	workspaceSlug: "acme",
	displayName: "Acme",
	providerType: "GITHUB",
	status: "ACTIVE",
	leaguesEnabled: false,
	leaderboardEnabled: false,
	practicesEnabled: true,
	mentorEnabled: false,
	achievementsEnabled: true,
	progressionEnabled: false,
};

function mockAchievementsRoute() {
	server.use(
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "octocat" }),
		),
		http.get("*/workspaces", () => HttpResponse.json([workspace])),
		http.get("*/workspaces/:workspaceSlug", () => HttpResponse.json(workspace)),
		http.get("*/workspaces/:workspaceSlug/connections/catalog", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/users", () =>
			HttpResponse.json([
				{ id: 1, login: "ada", name: "Ada Lovelace", teams: [], hidden: false },
				{ id: 2, login: "grace", name: "Grace Hopper", teams: [], hidden: false },
			]),
		),
	);
}

describe("workspace achievements route", () => {
	it.each([
		{
			outcome: "success",
			statuses: { ada: 204, grace: 204 },
			message: "Successfully dispatched recalculation for 2 users",
		},
		{
			outcome: "partial failure",
			statuses: { ada: 204, grace: 500 },
			message: "Dispatched recalculation for 1 users, 1 failed",
		},
		{
			outcome: "failure",
			statuses: { ada: 500, grace: 500 },
			message: "Dispatched recalculation for 0 users, 2 failed",
		},
	])(
		"reports bulk $outcome and makes the action available again",
		async ({ statuses, message }) => {
			mockAchievementsRoute();
			const user = userEvent.setup();
			const requests: string[] = [];
			server.use(
				http.post<{ workspaceSlug: string; login: string }>(
					"*/workspaces/:workspaceSlug/users/:login/achievements/recalculate",
					({ params }) => {
						requests.push(`${params.workspaceSlug}/${params.login}`);
						const responses: Record<string, number> = statuses;
						return new HttpResponse(null, { status: responses[params.login] });
					},
				),
			);
			renderRouteAt("/w/acme/admin/achievements");
			const recalculate = await screen.findByRole(
				"button",
				{ name: "Recalculate All" },
				ROUTE_RENDER_WAIT,
			);
			await waitFor(() => expect(recalculate.hasAttribute("disabled")).toBe(false));
			await user.click(recalculate);
			await waitFor(() => expect([...requests].sort()).toStrictEqual(["acme/ada", "acme/grace"]));
			await screen.findByText(message);
			await waitFor(() =>
				expect(
					screen.getByRole("button", { name: "Recalculate All" }).hasAttribute("disabled"),
				).toBe(false),
			);
		},
	);

	it("recalculates the selected member rather than the signed-in administrator", async () => {
		mockAchievementsRoute();
		const user = userEvent.setup();
		const requests: string[] = [];
		server.use(
			http.post<{ workspaceSlug: string; login: string }>(
				"*/workspaces/:workspaceSlug/users/:login/achievements/recalculate",
				({ params }) => {
					requests.push(`${params.workspaceSlug}/${params.login}`);
					return new HttpResponse(null, { status: 204 });
				},
			),
		);
		renderRouteAt("/w/acme/admin/achievements");
		const row = await screen.findByRole("row", { name: /Ada Lovelace/ }, ROUTE_RENDER_WAIT);
		const recalculate = within(row).getByRole("button", { name: "Recalculate" });
		await user.click(recalculate);
		await waitFor(() => expect(requests).toStrictEqual(["acme/ada"]));
		await waitFor(() =>
			expect(
				within(row).getByRole("button", { name: "Recalculate" }).hasAttribute("disabled"),
			).toBe(false),
		);
	});

	it("reloads definitions using the signed-in administrator", async () => {
		mockAchievementsRoute();
		const user = userEvent.setup();
		const requests: string[] = [];
		server.use(
			http.post<{ workspaceSlug: string; login: string }>(
				"*/workspaces/:workspaceSlug/users/:login/achievements/reload",
				({ params }) => {
					requests.push(`${params.workspaceSlug}/${params.login}`);
					return new HttpResponse(null, { status: 204 });
				},
			),
		);
		renderRouteAt("/w/acme/admin/achievements");
		const reload = await screen.findByRole(
			"button",
			{ name: "Reload Definitions" },
			ROUTE_RENDER_WAIT,
		);
		await waitFor(() => expect(reload.hasAttribute("disabled")).toBe(false));
		await user.click(reload);
		await waitFor(() => expect(requests).toStrictEqual(["acme/octocat"]));
		await waitFor(() =>
			expect(
				screen.getByRole("button", { name: "Reload Definitions" }).hasAttribute("disabled"),
			).toBe(false),
		);
	});
});
