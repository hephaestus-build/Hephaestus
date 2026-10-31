import { screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { beforeEach, expect, it, vi } from "vitest";

import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

// A case mounts the whole app chrome, whose route modules are imported lazily.
vi.setConfig({ testTimeout: 30_000 });

const NOTICE = "You cannot open the workspace “foreign”.";

beforeEach(() => {
	server.use(
		http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])),
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json(ownMembership("MEMBER")),
		),
	);
});

it("says that a link named a workspace the reader cannot open, and opens theirs", async () => {
	const { router } = renderRouteAtWithRouter("/w/foreign/activity");

	await screen.findByText(NOTICE, {}, ROUTE_RENDER_WAIT);
	screen.getByText("This is acme.");
	await waitFor(() => {
		expect(router.state.location.pathname).toBe("/w/acme/activity");
	}, ROUTE_RENDER_WAIT);
});

it("raises no notice when a link to such a workspace only preloads", async () => {
	const { router } = renderRouteAtWithRouter("/w/acme/activity");
	await screen.findByRole("heading", { level: 1 }, ROUTE_RENDER_WAIT);

	await router
		.preloadRoute({ to: "/w/$workspaceSlug/activity", params: { workspaceSlug: "foreign" } })
		.catch(() => undefined);

	// A toast renders a moment after it is raised, so wait as long as one would take to show.
	await expect(screen.findByText(NOTICE, {}, { timeout: 1000 })).rejects.toThrow(
		/Unable to find an element/u,
	);
	expect(router.state.location.pathname).toBe("/w/acme/activity");
});

it("says it on the home page when the reader has no workspace to open", async () => {
	server.use(http.get("*/workspaces", () => HttpResponse.json([])));
	const { router } = renderRouteAtWithRouter("/w/foreign/activity");

	await screen.findByText(NOTICE, {}, ROUTE_RENDER_WAIT);
	expect(screen.queryByText(/^This is /u)).toBeNull();
	await waitFor(() => {
		expect(router.state.location.pathname).toBe("/");
	}, ROUTE_RENDER_WAIT);
});
