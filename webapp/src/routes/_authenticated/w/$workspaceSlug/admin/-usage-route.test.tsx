import { fireEvent, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

// Mounting the real route pulls in the whole admin layout and its lazy modules.
vi.setConfig({ testTimeout: 15_000 });

const REPORT = {
	month: "2026-07",
	ownProviderMonthlyBudgetUsd: 10,
	instanceTotalCostUsd: 0,
	ownProviderTotalCostUsd: 1.5,
	instanceBudgetVerdict: "WITHIN",
	ownProviderBudgetVerdict: "WITHIN",
	instancePaused: false,
	ownProviderPaused: false,
	unpricedEventCount: 0,
	byJobType: [],
	byDay: [],
};

const REJECTION = "A cap above $1,000,000 is not accepted.";

const rejected = () =>
	HttpResponse.json(
		{ status: 400, title: "Bad Request", detail: REJECTION },
		{ status: 400, headers: { "Content-Type": "application/problem+json" } },
	);

function mockUsageRoute(onPutBudget: () => Promise<Response> | Response = rejected) {
	server.use(
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
		),
		http.get("*/workspaces/:workspaceSlug/llm/usage", () => HttpResponse.json(REPORT)),
		http.put("*/workspaces/:workspaceSlug/llm/budget", () => onPutBudget()),
	);
}

async function renderUsageRoute(onPutBudget?: () => Promise<Response> | Response) {
	mockUsageRoute(onPutBudget);
	renderRouteAt("/w/acme/admin/usage");
	await screen.findByRole("heading", { name: "AI usage" }, ROUTE_RENDER_WAIT);
	return screen.findByRole("button", { name: "Change cap" }, ROUTE_RENDER_WAIT);
}

const capField = () => screen.getByLabelText(/Monthly cap/iu);

/**
 * Only what is peculiar to *this* route. Where a rejection is reported — inline while the dialog is
 * open, as a toast once it is gone — belongs to `BudgetAmountDialog` and is asserted once, over the
 * instance budget.
 */
describe("workspace AI usage route", () => {
	it("does not re-show a dismissed rejection when the cap dialog is reopened", async () => {
		const changeCap = await renderUsageRoute();

		fireEvent.click(changeCap);
		fireEvent.change(capField(), { target: { value: "999999999" } });
		fireEvent.click(screen.getByRole("button", { name: "Save cap" }));
		await screen.findByText(REJECTION);

		fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
		await waitFor(() => expect(screen.queryByText(REJECTION)).toBeNull());

		fireEvent.click(screen.getByRole("button", { name: "Change cap" }));

		await screen.findByRole("button", { name: "Save cap" });
		expect(screen.queryByText(REJECTION)).toBeNull();
		expect(capField().getAttribute("aria-invalid")).toBe("false");
	});
});
