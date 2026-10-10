import { screen } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { Workspace } from "@/api/types.gen";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });

vi.mock("@/hooks/use-sync-events", () => ({ useSyncEvents: () => false }));

const CONFLICT =
	'This Slack workspace is already connected to the Hephaestus workspace "Staging" (staging). Disconnect Slack there before you connect it here.';

function failureRedirect() {
	const query = new URLSearchParams({
		status: "error",
		reason: "connected_elsewhere",
		description: CONFLICT,
		kind: "SLACK",
	});
	return `/integrations?${query}`;
}

function mockSlackPage() {
	const listed = workspaceListItem("intro-course");
	const workspace = {
		...listed,
		gitlabWebhookRegistered: false,
		hasPersonalAccessToken: false,
		hasSlackToken: false,
		publicActivityEnabled: false,
		practiceReviewAutoTriggerEnabled: false,
		practiceReviewManualTriggerEnabled: false,
		updatedAt: listed.createdAt,
	} satisfies Workspace;
	server.use(
		http.get("*/workspaces", () => HttpResponse.json([listed])),
		http.get("*/workspaces/:workspaceSlug", () => HttpResponse.json(workspace)),
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
		),
		http.get("*/workspaces/:workspaceSlug/connections/catalog", () => HttpResponse.json([])),
	);
}

describe("integration connection outcome", () => {
	afterEach(() => window.sessionStorage.clear());

	it("tells a Slack admin in their workspace why the Slack workspace could not be connected", async () => {
		mockSlackPage();

		const { router } = renderRouteAtWithRouter(`${failureRedirect()}&workspaceSlug=intro-course`);

		await screen.findByText(CONFLICT, undefined, ROUTE_RENDER_WAIT);
		screen.getByText("We could not connect Slack");
		expect(router.state.location.pathname).toBe("/w/intro-course/admin/integrations/slack");
	});

	it("shows the server's sentence rather than its reason code on the outcome page", async () => {
		renderRouteAtWithRouter(failureRedirect());

		await screen.findByRole(
			"heading",
			{ name: "We could not connect the integration" },
			ROUTE_RENDER_WAIT,
		);
		expect(screen.getAllByText(CONFLICT).length).toBeGreaterThan(0);
		expect(screen.queryByText("connected_elsewhere")).toBeNull();
	});
});
