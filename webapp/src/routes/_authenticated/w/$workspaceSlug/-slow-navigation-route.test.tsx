import { screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { expect, it, vi } from "vitest";

import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { deferred } from "@/test/async";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

// A case mounts the whole app chrome, whose route modules are imported lazily.
vi.setConfig({ testTimeout: 30_000 });

it("shows the page loading, not the previous page, while a slow navigation waits", async () => {
	const onboarding = deferred();
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme"), workspaceListItem("beta")]),
		),
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json(ownMembership("MEMBER")),
		),
		// The gate holds the navigation until this read answers.
		http.get("*/workspaces/beta/onboarding/me", async () => {
			await onboarding.promise;
			return HttpResponse.json({ needsSetup: false });
		}),
	);
	const { router } = renderRouteAtWithRouter("/w/acme/activity");
	await screen.findByRole("heading", { level: 1, name: "Activity" }, ROUTE_RENDER_WAIT);

	void router.navigate({ to: "/w/$workspaceSlug/activity", params: { workspaceSlug: "beta" } });

	await screen.findByText("Loading page…", {}, ROUTE_RENDER_WAIT);
	// React keeps the previous page mounted under the skeleton, hidden, so no query finds it.
	expect(screen.queryByRole("heading", { level: 1, name: "Activity" })).toBeNull();

	onboarding.resolve();
	await waitFor(() => {
		expect(screen.queryByText("Loading page…")).toBeNull();
	}, ROUTE_RENDER_WAIT);
	expect(router.state.location.pathname).toBe("/w/beta/activity");
	await screen.findByRole("heading", { level: 1 }, ROUTE_RENDER_WAIT);
});
