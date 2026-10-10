import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http, type PathParams } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { UpdateWorkspacePublicActivityRequest } from "@/api/types.gen";
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
		expect(
			within(screen.getByRole("region", { name: "Capabilities" })).queryByRole("switch"),
		).toBeNull();
		expect(screen.getByRole("link", { name: /Review settings/u }).getAttribute("href")).toBe(
			"/w/acme/admin/practices/review",
		);
	});

	it("says practice reviews are off when the workspace has not turned them on", async () => {
		renderSettingsRoute(false);

		await screen.findByText(/^Off\./u, undefined, ROUTE_RENDER_WAIT);
	});
});

describe("the public activity page setting", () => {
	let settings = { enabled: false, allowSearchEngines: false };
	let changes: unknown[] = [];

	beforeEach(() => {
		settings = { enabled: false, allowSearchEngines: false };
		changes = [];
		server.use(
			http.get("*/workspaces/acme/public-activity", () => HttpResponse.json(settings)),
			http.patch<PathParams, UpdateWorkspacePublicActivityRequest>(
				"*/workspaces/acme/public-activity",
				async ({ request }) => {
					const body = await request.json();
					changes.push(body);
					settings = {
						enabled: body.publicActivityEnabled,
						allowSearchEngines: body.allowSearchEngines,
					};
					return HttpResponse.json(settings);
				},
			),
			http.get("*/workspaces/acme/activity/public-hidden-count", () =>
				HttpResponse.json({ hiddenPeople: 3 }),
			),
		);
	});

	it("asks first, and turns the page on only once the admin has read what becomes public", async () => {
		const user = userEvent.setup();
		renderSettingsRoute(false);

		await user.click(
			await screen.findByRole("switch", { name: "Publish the page" }, ROUTE_RENDER_WAIT),
		);
		const dialog = await screen.findByRole("alertdialog");
		within(dialog).getByText(/Anyone can see the page, without signing in/u);
		expect(changes).toStrictEqual([]);
		await user.click(within(dialog).getByRole("button", { name: "Make public" }));

		await waitFor(() =>
			expect(changes).toStrictEqual([{ publicActivityEnabled: true, allowSearchEngines: false }]),
		);
		// Search engines stay off until the admin turns them on, and the count of hidden people shows.
		await screen.findByRole("switch", { name: "Allow search engines" });
		await screen.findByText("3");
	});

	it("turns the page off at once, and publishing again starts with search engines off", async () => {
		const user = userEvent.setup();
		settings = { enabled: true, allowSearchEngines: true };
		renderSettingsRoute(false);

		await user.click(
			await screen.findByRole("switch", { name: "Publish the page" }, ROUTE_RENDER_WAIT),
		);
		await waitFor(() =>
			expect(changes).toStrictEqual([{ publicActivityEnabled: false, allowSearchEngines: false }]),
		);
		expect(screen.queryByRole("alertdialog")).toBeNull();

		await user.click(screen.getByRole("switch", { name: "Publish the page" }));
		const dialog = await screen.findByRole("alertdialog");
		within(dialog).getByText(/Search engines are asked not to list it until you allow that/u);
		await user.click(within(dialog).getByRole("button", { name: "Make public" }));

		await waitFor(() => expect(changes).toHaveLength(2));
		expect(changes[1]).toStrictEqual({ publicActivityEnabled: true, allowSearchEngines: false });
		const searchEngines = await screen.findByRole("switch", { name: "Allow search engines" });
		expect(searchEngines.getAttribute("aria-checked")).toBe("false");
	});

	it("lets search engines in without turning the page off", async () => {
		const user = userEvent.setup();
		settings = { enabled: true, allowSearchEngines: false };
		renderSettingsRoute(false);

		await user.click(
			await screen.findByRole("switch", { name: "Allow search engines" }, ROUTE_RENDER_WAIT),
		);

		await waitFor(() =>
			expect(changes).toStrictEqual([{ publicActivityEnabled: true, allowSearchEngines: true }]),
		);
	});

	it("says nothing is public while the instance does not allow public pages", async () => {
		settings = { enabled: true, allowSearchEngines: false };
		renderSettingsRoute(false);

		await screen.findByText(
			/An instance administrator has not allowed/u,
			undefined,
			ROUTE_RENDER_WAIT,
		);
	});

	it("shows a person an admin hid without a membership again, which no other screen can", async () => {
		const user = userEvent.setup();
		const shown: string[] = [];
		server.use(
			http.get("*/workspaces/acme/activity/hidden-contributors", () =>
				HttpResponse.json([
					{ id: 11, login: "ada", name: "Ada Lovelace", avatarUrl: "", htmlUrl: "", email: "" },
				]),
			),
			http.patch("*/workspaces/acme/activity/people/11/public-visibility", ({ request }) => {
				shown.push(new URL(request.url).search);
				return new HttpResponse(null, { status: 204 });
			}),
		);
		renderSettingsRoute(false);

		await user.click(await screen.findByRole("button", { name: "Show again" }, ROUTE_RENDER_WAIT));

		await waitFor(() => expect(shown).toStrictEqual(["?hidden=false"]));
	});
});
