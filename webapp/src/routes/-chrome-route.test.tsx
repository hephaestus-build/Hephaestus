import { act, fireEvent, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { AdminWorkspaceView } from "@/api/types.gen";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt, renderRouteAtWithRouter } from "@/test/router-harness";

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
				HttpResponse.json(ownMembership("ADMIN")),
			),
		);
	});

	// The instance console replaces the switcher with its own header, so only /settings carries one.
	it("names the account's workspace in the switcher", async () => {
		renderRouteAt("/settings");

		await screen.findByRole("button", { name: /Acme/u }, ROUTE_RENDER_WAIT);
	});

	it.each(["/settings", "/admin"])("keeps Activity reachable on %s", async (path) => {
		const user = userEvent.setup();
		renderRouteAt(path);

		await user.click(await screen.findByRole("button", { name: "Account" }, ROUTE_RENDER_WAIT));

		const activity = await screen.findByRole("menuitem", { name: "Activity" });
		expect(activity.getAttribute("aria-disabled")).toBeNull();
		expect(activity.closest("a")?.getAttribute("href")).toBe("/w/acme/activity");
	});
});

/** The browser switches tabs with Cmd or Ctrl and a number, so the app takes no such key for itself. */
describe("app chrome with two workspaces", () => {
	beforeEach(() => {
		server.use(
			http.get("*/workspaces", () =>
				HttpResponse.json([
					workspaceListItem("acme", { displayName: "Acme" }),
					workspaceListItem("beta", { id: 2, displayName: "Beta" }),
				]),
			),
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json(ownMembership("ADMIN")),
			),
		);
	});

	it.each([
		["Ctrl", { ctrlKey: true }],
		["Cmd", { metaKey: true }],
	])("leaves %s+2 to the browser", async (_name, modifier) => {
		const { router } = renderRouteAtWithRouter("/settings");
		await screen.findByRole("button", { name: /Acme/u }, ROUTE_RENDER_WAIT);

		let notPrevented = false;
		await act(async () => {
			notPrevented = fireEvent.keyDown(document.body, { key: "2", ...modifier });
		});

		expect(notPrevented).toBe(true);
		expect(router.state.location.pathname).toBe("/settings");
	});
});

/** Cmd or Ctrl and B toggles the sidebar, except while the reader types in a field. */
describe("app chrome sidebar shortcut", () => {
	beforeEach(() => {
		server.use(
			http.get("*/workspaces", () =>
				HttpResponse.json([workspaceListItem("acme", { displayName: "Acme" })]),
			),
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json(ownMembership("ADMIN")),
			),
		);
	});

	it.each([
		["Ctrl", { ctrlKey: true }],
		["Cmd", { metaKey: true }],
	])("announces %s+B on the toggle and leaves it to a field", async (_name, modifier) => {
		renderRouteAt("/settings");
		// The sidebar's rail carries the same name; the header button is the one that announces the keys.
		await screen.findByRole("button", { name: /Acme/u }, ROUTE_RENDER_WAIT);
		const toggle = screen
			.getAllByRole("button", { name: "Toggle sidebar" })
			.find((button) => button.dataset.sidebar === "trigger");
		expect(toggle?.getAttribute("aria-keyshortcuts")).toBe("Control+B Meta+B");

		const field = document.createElement("textarea");
		document.body.append(field);
		let fieldKept = false;
		let pageTaken = false;
		await act(async () => {
			fieldKept = fireEvent.keyDown(field, { key: "b", ...modifier });
			pageTaken = !fireEvent.keyDown(document.body, { key: "b", ...modifier });
		});
		field.remove();

		expect(fieldKept).toBe(true);
		expect(pageTaken).toBe(true);
	});
});
