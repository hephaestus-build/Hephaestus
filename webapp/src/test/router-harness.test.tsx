import type { AnyRouter } from "@tanstack/react-router";
import { screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";

import { deferred } from "./async";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "./router-harness";

let left: AnyRouter | undefined;

/**
 * Opens a workspace page, then starts a navigation to a second workspace whose onboarding read the
 * test holds, so the navigation is still loading when the test ends. The read answers `after` the
 * test, on whichever timers the test runs.
 */
async function leaveANavigationLoading(after: number) {
	const onboarding = deferred();
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json([
				workspaceListItem("acme", { practicesEnabled: true }),
				workspaceListItem("beta", { practicesEnabled: true }),
			]),
		),
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json(ownMembership("MEMBER")),
		),
		http.get("*/workspaces/beta/onboarding/me", async () => {
			await onboarding.promise;
			return HttpResponse.json({ needsSetup: false });
		}),
	);
	const { router } = renderRouteAtWithRouter("/w/acme/activity");
	await screen.findByRole("heading", { level: 1 }, ROUTE_RENDER_WAIT);
	left = router;

	void router.navigate({ to: "/w/$workspaceSlug/activity", params: { workspaceSlug: "beta" } });
	await waitFor(() => {
		expect(router.state.location.pathname).toBe("/w/beta/activity");
	});
	setTimeout(() => {
		onboarding.resolve();
	}, after);
	return router;
}

beforeEach(() => {
	vi.useRealTimers();
});

// Each pair runs in order: the second test reads what the first one left behind.
describe("renderRouteAtWithRouter", () => {
	it("leaves a navigation that is still loading when the test ends", async () => {
		const router = await leaveANavigationLoading(300);

		expect(router.state.status).toBe("pending");
	});

	it("starts the next test only once that navigation has settled", () => {
		expect(left?.state.status).toBe("idle");
	});

	it("leaves a navigation that is still loading on fake timers", async () => {
		const router = await leaveANavigationLoading(300);
		vi.useFakeTimers();

		expect(router.state.status).toBe("pending");
	});

	it("settles that navigation on fake timers too", () => {
		expect(left?.state.status).toBe("idle");
	});
});
