import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { assert, expect, it, vi } from "vitest";

import type { WorkspaceOnboarding } from "@/api/types.gen";
import { workspaceOnboarding } from "@/mocks/fixtures/onboarding";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { sleep } from "@/test/async";
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

/**
 * The toast that holds this text: the page under it has a Retry of its own. Sonner removes a
 * dismissed toast a few frames late, so the toast must still be there well after those frames.
 */
async function toastSaying(text: string): Promise<HTMLElement> {
	await screen.findByText(text, undefined, ROUTE_RENDER_WAIT);
	await sleep(500);
	const toast = screen.getByText(text).closest<HTMLElement>("[data-sonner-toast]");
	assert(toast !== null, `"${text}" is not in a toast.`);
	expect(toast.dataset.removed).not.toBe("true");
	return toast;
}

it("offers Retry, then a page load once Retry did not help, and goes when Heph is back", async () => {
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
	const openHeph = async () =>
		user.click(
			await screen.findByRole("button", { name: "Open Heph, AI mentor" }, ROUTE_RENDER_WAIT),
		);

	await openHeph();
	const first = await toastSaying("We could not show Heph.");
	expect(screen.queryByRole("button", { name: "Open Heph, AI mentor" })).toBeNull();
	// The page under it still works.
	screen.getByRole("heading", { name: "Teams" });

	// Retry renders the panel again: the launcher is back and the message goes.
	await user.click(within(first).getByRole("button", { name: "Retry" }));
	await screen.findByRole("button", { name: "Open Heph, AI mentor" });
	await waitFor(() => expect(screen.queryByText("We could not show Heph.")).toBeNull());

	// It fails again on open, so the message says Retry ran and offers a page load instead.
	await openHeph();
	const again = await toastSaying("Heph still could not be shown.");
	within(again).getByRole("button", { name: "Reload page" });
	expect(within(again).queryByRole("button", { name: "Retry" })).toBeNull();
});
