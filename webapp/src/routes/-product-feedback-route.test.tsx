import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { FeedbackRequest } from "@/api/types.gen";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

// A case mounts the whole app chrome, whose route modules are imported lazily.
vi.setConfig({ testTimeout: 30_000 });

describe("product feedback sent from a workspace page", () => {
	let sent: FeedbackRequest[];

	beforeEach(() => {
		sent = [];
		server.use(
			http.get("*/workspaces", () =>
				HttpResponse.json([workspaceListItem("acme", { displayName: "Acme" })]),
			),
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json(ownMembership("MEMBER")),
			),
			http.post("*/workspaces/acme/product-feedback", async ({ request }) => {
				sent.push((await request.json()) as FeedbackRequest);
				return new HttpResponse(null, { status: 202 });
			}),
		);
	});

	it("attaches the page as its route, without the workspace in the address", async () => {
		const user = userEvent.setup();
		renderRouteAt("/w/acme/activity?period=quarter");

		await user.click(
			await screen.findByRole("button", { name: "Product feedback" }, ROUTE_RENDER_WAIT),
		);
		await user.click(await screen.findByRole("menuitem", { name: "Report a bug" }));
		await user.type(screen.getByRole("textbox", { name: "What happened?" }), "The list is empty.");
		expect(screen.getByText("/w/$workspaceSlug/activity")).toBeDefined();
		await user.click(screen.getByRole("button", { name: "Send" }));

		await waitFor(() => expect(sent).toHaveLength(1));
		expect(sent[0]).toMatchObject({
			kind: "BUG",
			message: "The list is empty.",
			pagePath: "/w/$workspaceSlug/activity",
		});
		expect(JSON.stringify(sent[0])).not.toContain("acme");
	});
});
