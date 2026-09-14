import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { SurveyInvitation } from "@/api/types.gen";
import { surveyInvitation } from "@/components/feedback/product-survey-fixtures";
import type { Wire } from "@/lib/dates";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { unauthenticatedUser } from "@/mocks/handlers";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt, renderRouteAtWithRouter } from "@/test/router-harness";

// A case mounts the whole app chrome, whose route modules are imported lazily.
vi.setConfig({ testTimeout: 30_000 });

const unseen: Wire<SurveyInvitation> = {
	...surveyInvitation,
	endsAt: surveyInvitation.endsAt.toISOString(),
	seen: false,
};

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

	it("preserves the survey deep link through the sign-in guard", async () => {
		server.use(unauthenticatedUser);
		const destination = `/w/acme?survey=${unseen.id}`;
		const { router } = renderRouteAtWithRouter(destination);
		await waitFor(() => expect(router.state.location.pathname).toBe("/login"), ROUTE_RENDER_WAIT);
		expect(router.state.location.search.returnTo).toContain(destination);
	});

	it("opens the email-linked survey rather than the first available survey", async () => {
		const other = {
			...unseen,
			id: "11111111-1111-4111-8111-111111111111",
			title: "Another survey",
		};
		server.use(
			http.get("*/workspaces/acme/product-feedback/surveys", () =>
				HttpResponse.json([other, unseen]),
			),
		);
		renderRouteAt(`/w/acme?survey=${unseen.id}`);
		const dialog = await screen.findByRole("dialog", {}, ROUTE_RENDER_WAIT);
		expect(dialog.textContent).toContain(unseen.title);
		expect(dialog.textContent).not.toContain(other.title);
		expect(screen.queryByText(`New survey: ${other.title}`)).toBeNull();
	});

	it("does not substitute another survey when the linked survey is unavailable", async () => {
		server.use(
			http.get("*/workspaces/acme/product-feedback/surveys", () => HttpResponse.json([unseen])),
		);
		renderRouteAt("/w/acme?survey=11111111-1111-4111-8111-111111111111");
		await screen.findByRole("heading", { name: "Survey unavailable" }, ROUTE_RENDER_WAIT);
		expect(screen.queryByText(`New survey: ${unseen.title}`)).toBeNull();
		expect(acknowledgements).toStrictEqual([]);
	});

	it("offers a retry instead of claiming the linked survey is unavailable after a failed lookup", async () => {
		const user = userEvent.setup();
		server.use(
			http.get("*/workspaces/acme/product-feedback/surveys", () =>
				HttpResponse.json({ status: 503 }, { status: 503 }),
			),
		);
		renderRouteAt(`/w/acme?survey=${unseen.id}`);
		await screen.findByRole("heading", { name: "Could not load survey" }, ROUTE_RENDER_WAIT);
		expect(screen.queryByText(/no longer offered/)).toBeNull();
		server.use(
			http.get("*/workspaces/acme/product-feedback/surveys", () => HttpResponse.json([unseen])),
		);
		await user.click(screen.getByRole("button", { name: "Retry" }));
		await screen.findByRole("heading", { name: unseen.title });
	});

	it("nudges once for an unseen invitation, records it, and opens the survey from the nudge", async () => {
		server.use(
			http.get("*/workspaces/acme/product-feedback/surveys", () => HttpResponse.json([unseen])),
		);
		const user = userEvent.setup();
		renderRouteAt("/settings");

		await screen.findByRole("button", { name: "Feedback, 1 survey waiting" }, ROUTE_RENDER_WAIT);
		await screen.findByText(`New survey: ${surveyInvitation.title}`);
		await waitFor(() => expect(acknowledgements).toStrictEqual([surveyInvitation.id]));
		expect(screen.queryByRole("dialog")).toBeNull();

		await user.click(screen.getByRole("button", { name: "Take survey" }));

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
			await screen.findByRole("button", { name: "Feedback, 1 survey waiting" }, ROUTE_RENDER_WAIT),
		);

		const item = await screen.findByRole("menuitem", { name: /Help improve practice feedback/ });
		expect(item.textContent).toContain("4 questions · about 2 minutes");
		expect(screen.queryByText(/New survey:/)).toBeNull();
		expect(acknowledgements).toStrictEqual([]);
	});
});
