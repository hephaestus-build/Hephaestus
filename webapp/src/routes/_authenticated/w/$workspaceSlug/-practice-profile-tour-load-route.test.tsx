import { fireEvent, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { beforeAll, expect, it, vi } from "vitest";

import { toast } from "@/lib/toast";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { OVERVIEW_FIXTURE } from "@/stories/practice-profile-story-mock-data";
import { loadRouteCode, ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

beforeAll(async () => loadRouteCode("/w/acme/practice-profile"), ROUTE_RENDER_WAIT.timeout);

// In a file of its own: `lazy` keeps a failed import for the life of the module.
vi.mock("@/components/tour/PracticeProfileTour", () => {
	throw new Error("The chunk did not load");
});

function servePracticeProfile() {
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme", { practicesEnabled: true })]),
		),
		http.get("*/workspaces/:workspaceSlug/members/me", () => HttpResponse.json(ownMembership())),
		http.get("*/workspaces/:workspaceSlug/practices/reviewed", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/practice-groups/standings", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/practices/standings", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/practice-profile/overview", () =>
			HttpResponse.json(OVERVIEW_FIXTURE),
		),
		http.get("*/workspaces/:workspaceSlug/practices/feedback/practice-page", () =>
			HttpResponse.json([]),
		),
	);
}

it("says that the tour could not load, and leaves the page usable", async () => {
	const failure = vi.spyOn(toast, "error");
	servePracticeProfile();
	renderRouteAtWithRouter("/w/acme/practice-profile");

	fireEvent.click(await screen.findByRole("button", { name: "Take the tour" }, ROUTE_RENDER_WAIT));

	await waitFor(() =>
		expect(failure).toHaveBeenCalledWith("The tour could not load. Reload the page to try again."),
	);
	expect(screen.queryByRole("dialog", { name: "Guided tour" })).toBeNull();
	// The page stays: this throws if the boundary took it down with the tour.
	screen.getByRole("heading", { level: 1, name: "Practice profile" });
});

it("says so when a chapter that the address asked for could not load, and lets go of the address", async () => {
	const failure = vi.spyOn(toast, "error");
	servePracticeProfile();
	const { router } = renderRouteAtWithRouter("/w/acme/practice-profile?tour=your-profile");

	await waitFor(
		() =>
			expect(failure).toHaveBeenCalledWith(
				"The tour could not load. Reload the page to try again.",
			),
		ROUTE_RENDER_WAIT,
	);
	await waitFor(() => expect(router.state.location.search.tour).toBeUndefined());
	screen.getByRole("heading", { level: 1, name: "Practice profile" });
});
