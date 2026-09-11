import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { SurveyInvitation } from "@/api/types.gen";
import { surveyInvitation } from "@/components/feedback/product-survey-fixtures";
import type { Wire } from "@/lib/dates";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

// A case mounts the whole app chrome, whose route modules are imported lazily.
vi.setConfig({ testTimeout: 30_000 });

const unseen: Wire<SurveyInvitation> = {
	...surveyInvitation,
	endsAt: surveyInvitation.endsAt.toISOString(),
	seen: false,
};

/**
 * The header is where a survey invitation lives. A member is told about a new one exactly once —
 * with a quiet toast the server remembers — and everything else waits for them to open the menu.
 */
describe("survey invitations in the app chrome", () => {
	let acknowledgements: string[];

	beforeEach(() => {
		acknowledgements = [];
		server.use(
			http.get("*/workspaces", () =>
				HttpResponse.json([workspaceListItem("acme", { displayName: "Acme" })]),
			),
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json({ role: "MEMBER", userId: 42, userLogin: "ada", userName: "Ada" }),
			),
			http.put("*/workspaces/acme/product-feedback/surveys/:id/invitation", ({ params }) => {
				acknowledgements.push(String(params.id));
				return new HttpResponse(null, { status: 204 });
			}),
		);
	});

	it("nudges once for an unseen invitation, records it, and opens the survey from the nudge", async () => {
		server.use(
			http.get("*/workspaces/acme/product-feedback/surveys", () => HttpResponse.json([unseen])),
		);
		const user = userEvent.setup();
		renderRouteAt("/settings");

		await screen.findByRole("button", { name: "Feedback, 1 open survey" }, ROUTE_RENDER_WAIT);
		const nudge = await screen.findByText(`New survey: ${surveyInvitation.title}`);
		expect(nudge.textContent).toContain(surveyInvitation.title);
		await waitFor(() => expect(acknowledgements).toStrictEqual([surveyInvitation.id]));
		expect(screen.queryByRole("dialog")).toBeNull();

		// jsdom has no pointer capture, which the toast's swipe handling asks for on pointerdown.
		screen.getByRole("button", { name: "Take survey" }).focus();
		await user.keyboard("{Enter}");

		const dialog = await screen.findByRole("dialog");
		expect(dialog.textContent).toContain(surveyInvitation.title);
		expect(dialog.textContent).toContain("4 questions · about 2 minutes");
	});

	it("stays quiet for an invitation the account has already been shown", async () => {
		server.use(
			http.get("*/workspaces/acme/product-feedback/surveys", () =>
				HttpResponse.json([{ ...unseen, seen: true }]),
			),
		);
		const user = userEvent.setup();
		renderRouteAt("/settings");

		await user.click(
			await screen.findByRole("button", { name: "Feedback, 1 open survey" }, ROUTE_RENDER_WAIT),
		);

		const item = await screen.findByRole("menuitem", { name: /Help improve practice feedback/ });
		expect(item.textContent).toContain("4 questions · about 2 minutes");
		expect(screen.queryByText(/New survey:/)).toBeNull();
		expect(acknowledgements).toStrictEqual([]);
	});
});
