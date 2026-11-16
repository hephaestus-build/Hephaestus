import { screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { beforeAll, expect, it, vi } from "vitest";

import { workspaceOnboarding } from "@/mocks/fixtures/onboarding";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import {
	OVERVIEW_FIXTURE,
	READY_REVIEW_READINESS,
} from "@/stories/practice-profile-story-mock-data";
import { loadRouteCode, ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

beforeAll(async () => loadRouteCode("/w/acme/practice-profile"), ROUTE_RENDER_WAIT.timeout);

// In a file of its own: `lazy` keeps a failed import for the life of the module.
vi.mock("@/components/onboarding/GetStartedSection", () => {
	throw new Error("The chunk did not load");
});

it("says why no review runs on the page itself when the Get started list could not load", async () => {
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme", { practicesEnabled: true })]),
		),
		http.get("*/workspaces/:workspaceSlug/members/me", () => HttpResponse.json(ownMembership())),
		http.get("*/workspaces/:workspaceSlug/onboarding/me", () =>
			HttpResponse.json({ ...workspaceOnboarding(), enabled: true, aiChoiceRequired: true }),
		),
		http.get("*/workspaces/:workspaceSlug/practices/reviewed", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/practice-groups/standings", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/practices/standings", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/practice-profile/overview", () =>
			HttpResponse.json({
				...OVERVIEW_FIXTURE,
				latestReview: null,
				reviewReadiness: { ...READY_REVIEW_READINESS, blocker: "CHOICE_REQUIRED" },
			}),
		),
		http.get("*/workspaces/:workspaceSlug/practices/feedback/practice-page", () =>
			HttpResponse.json([]),
		),
	);
	renderRouteAtWithRouter("/w/acme/practice-profile");

	await screen.findByText("Choose your AI to get reviews", undefined, ROUTE_RENDER_WAIT);
	expect(screen.queryByRole("region", { name: "Get started" })).toBeNull();
});
