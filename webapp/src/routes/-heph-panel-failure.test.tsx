import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { assert, expect, it, vi } from "vitest";

import type { WorkspaceOnboarding } from "@/api/types.gen";
import { workspaceOnboarding } from "@/mocks/fixtures/onboarding";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { MentorFake } from "@/test/mentor-server";
import { renderRouteAtWithRouter, ROUTE_RENDER_WAIT } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });

// The conversation renders only while the panel is open, so this fails the panel the moment the
// developer opens it.
vi.mock("./-HephConversationView", () => ({
	MainConversation: () => {
		throw new Error("Heph conversation failed to render");
	},
	ThreadConversation: () => null,
}));

it("shows a message with Retry in the launcher's place when the open panel fails", async () => {
	const user = userEvent.setup();
	const ready: WorkspaceOnboarding = {
		...workspaceOnboarding(),
		aiOptions: [{ choice: "CLOUD", mentorReady: true, practiceReviewsReady: false, models: [] }],
	};
	server.use(
		http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])),
		http.get("*/user/features", () => HttpResponse.json({})),
		http.get("*/workspaces/:workspaceSlug/members/me", () => HttpResponse.json(ownMembership())),
		http.get("*/workspaces/:workspaceSlug/onboarding/me", () => HttpResponse.json(ready)),
		...new MentorFake("acme").handlers(),
	);
	renderRouteAtWithRouter("/w/acme/teams");
	await user.click(
		await screen.findByRole("button", { name: "Open Heph, AI mentor" }, ROUTE_RENDER_WAIT),
	);

	// By text, not by role: the page can hold other alerts of its own.
	const title = await screen.findByText("We could not show Heph", undefined, ROUTE_RENDER_WAIT);
	const message = title.closest<HTMLElement>('[role="alert"]');
	assert(message !== null, "The message is not announced as an alert.");
	expect(screen.queryByRole("button", { name: "Open Heph, AI mentor" })).toBeNull();
	// The page under it still works.
	screen.getByRole("heading", { name: "Teams" });

	await user.click(within(message).getByRole("button", { name: "Retry" }));
	await screen.findByRole("button", { name: "Open Heph, AI mentor" });
	expect(screen.queryByText("We could not show Heph")).toBeNull();
});
