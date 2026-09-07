import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import {
	artifactTrace,
	tracedArtifactPage,
	tracedArtifacts,
} from "@/components/practice-trace/story-mock-data";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt, renderRouteAtWithRouter } from "@/test/router-harness";

// Mounting the real route pulls in the whole app shell and its lazy modules.
vi.setConfig({ testTimeout: 15_000 });

beforeEach(() => {
	server.use(
		// A plain MEMBER: this surface is deliberately not behind the admin layout's role guard.
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "MEMBER", userId: 1, userLogin: "ada", userName: "Ada" }),
		),
		// The surface exists only where practices review the work, and the shared fixture has them
		// off, so every case below has to say that this workspace reviews.
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme", { practicesEnabled: true })]),
		),
		http.get("*/workspaces/:workspaceSlug/practices/trace", () =>
			HttpResponse.json(tracedArtifactPage()),
		),
		http.get("*/workspaces/:workspaceSlug/practices/trace/:artifactKind/:artifactId", () =>
			HttpResponse.json(artifactTrace),
		),
	);
});

describe("review activity routes", () => {
	it("sends a reader away when this workspace does not review practices", async () => {
		server.use(
			http.get("*/workspaces", () =>
				HttpResponse.json([workspaceListItem("acme", { practicesEnabled: false })]),
			),
		);
		const { router } = renderRouteAtWithRouter("/w/acme/reviews");

		// With practices off the surface does not exist here, so the reader ends up on the workspace
		// home rather than on a page whose only content could be an explanation of its own emptiness.
		await vi.waitFor(
			() => expect(router.state.location.pathname).toBe("/w/acme"),
			ROUTE_RENDER_WAIT,
		);
	});

	it("lists recorded work for a member", async () => {
		renderRouteAt("/w/acme/reviews");

		await screen.findByRole("heading", { name: "Review activity" }, ROUTE_RENDER_WAIT);
		await screen.findByRole("link", { name: /Member-facing review activity/ }, ROUTE_RENDER_WAIT);
	});

	/** Page 2 of "issues only" stays issues only: the filter lives in the router's search state. */
	it("keeps the work-type filter when the reader turns the page", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/trace", () =>
				HttpResponse.json({
					content: tracedArtifacts,
					page: { number: 0, size: 20, totalElements: 45, totalPages: 3 },
				}),
			),
		);

		renderRouteAt("/w/acme/reviews?kind=scm.issue");

		const next = await screen.findByRole("link", { name: "Go to next page" }, ROUTE_RENDER_WAIT);
		expect(next.getAttribute("href")).toBe("/w/acme/reviews?kind=scm.issue&page=1");
		// Page 1 is the default, so it is spelled by leaving `page` out rather than by `page=0`.
		expect(screen.getByRole("link", { name: "Go to page 1" }).getAttribute("href")).toBe(
			"/w/acme/reviews?kind=scm.issue",
		);
	});

	it("carries a dotted artifact kind through the URL into the detail view", async () => {
		renderRouteAt("/w/acme/reviews/scm.pull_request/1423");

		// A dormant practice is asserted, not a delivering one: showing the quiet ones is the point.
		await screen.findByText("Discussion hygiene", undefined, ROUTE_RENDER_WAIT);
		screen.getByText("Waiting on a connection");
	});

	it("refuses an artifact id that is not a positive integer", async () => {
		renderRouteAt("/w/acme/reviews/scm.pull_request/not-a-number");

		await screen.findByRole("heading", { name: "Page Not Found" }, ROUTE_RENDER_WAIT);
	});
});

it("changes the work filter without resetting scroll", async () => {
	const { router } = renderRouteAtWithRouter("/w/acme/reviews");
	const control = await screen.findByRole("combobox", { name: "Show" }, ROUTE_RENDER_WAIT);
	const scroll = vi.spyOn(window, "scrollTo").mockReturnValue(undefined);
	await userEvent.click(control);
	await userEvent.click(await screen.findByRole("option", { name: "Issues" }));
	await vi.waitFor(() => expect(router.state.location.search).toMatchObject({ kind: "scm.issue" }));
	expect(scroll).not.toHaveBeenCalled();
	scroll.mockRestore();
});
