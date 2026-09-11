import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { AdminWorkspaceView } from "@/api/types.gen";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

// A case mounts the whole app chrome, whose route modules are imported lazily.
vi.setConfig({ testTimeout: 30_000 });

/**
 * `/settings` and the instance console name no workspace in their URL, and the chrome renders on
 * both — so a member of workspaces must not be told there they belong to none.
 */
describe("app chrome on a route with no workspace in the URL", () => {
	beforeEach(() => {
		server.use(
			http.get("*/admin/workspaces", () => HttpResponse.json<AdminWorkspaceView[]>([])),
			http.get("*/workspaces", () =>
				HttpResponse.json([workspaceListItem("acme", { displayName: "Acme" })]),
			),
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json({
					role: "ADMIN",
					userId: 42,
					userLogin: "ada",
					userName: "Ada Lovelace",
				}),
			),
		);
	});

	// The instance console replaces the switcher with its own header, so only /settings carries one.
	it("names the account's workspace in the switcher", async () => {
		renderRouteAt("/settings");

		await screen.findByRole("button", { name: /Acme/ }, ROUTE_RENDER_WAIT);
	});

	it.each(["/settings", "/admin"])("keeps My Profile reachable on %s", async (path) => {
		const user = userEvent.setup();
		renderRouteAt(path);

		await user.click(await screen.findByRole("button", { name: "Account" }, ROUTE_RENDER_WAIT));

		const profile = await screen.findByRole("menuitem", { name: "My Profile" });
		expect(profile.getAttribute("aria-disabled")).toBeNull();
		expect(profile.closest("a")?.getAttribute("href")).toBe("/w/acme/user/ada");
	});
});
