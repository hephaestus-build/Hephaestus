import { fireEvent, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { beforeAll, beforeEach, describe, expect, it } from "vitest";

import type { WorkspaceLlmUsageReport } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt, loadRouteCode } from "@/test/router-harness";

// Mounting the real route pulls in the whole admin layout and its lazy modules.
beforeAll(async () => loadRouteCode("/w/acme/admin/usage"), ROUTE_RENDER_WAIT.timeout);

const REPORT: Wire<WorkspaceLlmUsageReport> = {
	month: "2026-07",
	ownProviderMonthlyBudgetUsd: 10,
	instanceTotalCostUsd: 0,
	instanceUnmeasured: { calls: 0, upperBoundUsd: 0 },
	ownProviderUnmeasured: { calls: 0, upperBoundUsd: 0 },
	ownProviderTotalCostUsd: 1.5,
	instanceBudgetVerdict: "WITHIN",
	ownProviderBudgetVerdict: "WITHIN",
	instancePaused: false,
	ownProviderPaused: false,
	ownProviderInUse: true,
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

function mockUsageRoute() {
	server.use(
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json(ownMembership("ADMIN")),
		),
		http.get("*/workspaces/:workspaceSlug/llm/usage", () => HttpResponse.json(REPORT)),
		http.put("*/workspaces/:workspaceSlug/llm/budget", rejected),
	);
}

async function renderUsageRoute() {
	mockUsageRoute();
	renderRouteAt("/w/acme/admin/usage");
	await screen.findByRole("heading", { name: "AI usage" }, ROUTE_RENDER_WAIT);
	return screen.findByRole("button", { name: "Change provider cap" }, ROUTE_RENDER_WAIT);
}

const capField = () => screen.getByLabelText(/Monthly cap/iu);

beforeEach(() => {
	server.use(http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])));
});

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

		fireEvent.click(screen.getByRole("button", { name: "Change provider cap" }));

		await screen.findByRole("button", { name: "Save cap" });
		expect(screen.queryByText(REJECTION)).toBeNull();
		expect(capField().getAttribute("aria-invalid")).toBe("false");
	});

	it("offers a provider cap for a connected provider before its first call", async () => {
		mockReport({
			...REPORT,
			ownProviderMonthlyBudgetUsd: undefined,
			ownProviderTotalCostUsd: 0,
			ownProviderInUse: true,
		});
		renderRouteAt("/w/acme/admin/usage");

		await screen.findByRole("button", { name: "Set provider cap" }, ROUTE_RENDER_WAIT);
	});
});

function mockReport(report: Wire<WorkspaceLlmUsageReport>, requestedMonths: string[] = []) {
	server.use(
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json(ownMembership("ADMIN")),
		),
		http.get("*/workspaces/:workspaceSlug/llm/usage", ({ request }) => {
			requestedMonths.push(new URL(request.url).searchParams.get("month") ?? "");
			return HttpResponse.json(report);
		}),
	);
}

describe("workspace AI usage route, the report", () => {
	it("asks for the month in the address and lists each use from the wire", async () => {
		const requestedMonths: string[] = [];
		mockReport(
			{
				...REPORT,
				byJobType: [
					{
						jobType: "PULL_REQUEST_REVIEW",
						instanceTotalCostUsd: 0,
						ownProviderTotalCostUsd: 1.5,
						unpricedEventCount: 0,
						inputTokens: 90_000,
						outputTokens: 4000,
						cacheReadTokens: 0,
						cacheWriteTokens: 0,
						totalCalls: 60,
						events: 5,
					},
				],
			},
			requestedMonths,
		);
		renderRouteAt("/w/acme/admin/usage?month=2026-07");

		const use = await screen.findByText("Pull request review", undefined, ROUTE_RENDER_WAIT);
		expect(requestedMonths).toContain("2026-07");
		expect(use.closest("table")?.querySelector("caption")?.textContent).toBe("AI spend by use");
	});

	it("says the report could not load, and offers a retry", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json(ownMembership("ADMIN")),
			),
			http.get("*/workspaces/:workspaceSlug/llm/usage", () =>
				HttpResponse.json(
					{ status: 500, title: "Internal Server Error", detail: "The ledger is down." },
					{ status: 500, headers: { "Content-Type": "application/problem+json" } },
				),
			),
		);
		renderRouteAt("/w/acme/admin/usage");

		await screen.findByText("We could not load AI usage", undefined, ROUTE_RENDER_WAIT);
		screen.getByRole("button", { name: /retry/iu });
	});
});
