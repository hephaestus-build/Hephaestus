import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";

import type { WorkspaceAccessPolicySettings } from "@/api/types.gen";
import { server } from "@/mocks/server";
import { renderRouteAtWithRouter, ROUTE_RENDER_WAIT } from "@/test/router-harness";

const settings = {
	primaryRegistrationId: "github",
	introductionMarkdown: "Our code of conduct",
	acknowledgementLabel: "I agree",
	maintainerTeamId: 1,
	requestableTeamIds: [],
	requiredLinks: [],
	notices: [],
	maximumDurationDays: 90,
	reminderDays: 14,
	adminMailbox: "admins@example.test",
} satisfies WorkspaceAccessPolicySettings;

function administration(role: "ADMIN" | "OWNER", applicantId = 99) {
	server.use(
		http.get("*/workspaces/test/members/me", () => HttpResponse.json({ role, accountId: 42 })),
		http.get("*/workspaces/test/access-policy", () =>
			HttpResponse.json({ enabled: true, emailConfigured: true, version: 3, settings }),
		),
		http.get("*/workspaces/test/access-requests/options", () =>
			HttpResponse.json({
				maintainers: [{ accountId: 2, displayName: "Sam Maintainer" }],
				requestableTeams: [],
				maximumDurationDays: 90,
			}),
		),
		http.get("*/workspaces/test/access-requests", () =>
			HttpResponse.json([
				{
					id: 1,
					accountId: applicantId,
					displayName: "Applicant",
					status: "SUBMITTED",
					version: 0,
					policyVersion: 3,
					submittedAt: "2026-09-01T00:00:00Z",
					requestedDetails: {
						maintainerAccountId: 2,
						teamIds: [],
						expiresAt: "2026-10-01T00:00:00Z",
					},
				},
			]),
		),
		http.get("*/workspaces/test/access-requests/1/notifications", () => HttpResponse.json([])),
		http.get("*/workspaces/test/teams", () =>
			HttpResponse.json([
				{
					id: 1,
					name: "Maintainers",
					labels: [],
					members: [],
					repositories: [],
					hidden: false,
					membershipCount: 0,
					repoPermissionCount: 0,
				},
			]),
		),
	);
}

describe("workspace access administration", () => {
	it("lets administrators review requests without offering owner-only policy writes", async () => {
		administration("ADMIN");
		renderRouteAtWithRouter("/w/test/admin/access");
		await userEvent.click(
			await screen.findByRole("button", { name: "Request policy" }, ROUTE_RENDER_WAIT),
		);
		expect((await screen.findByText(/Only a workspace owner can configure/)).textContent).toContain(
			"enabled",
		);
		expect(screen.queryByRole("button", { name: "Save access policy" })).toBeNull();
	});

	it("hides review actions on the current account's own request", async () => {
		administration("OWNER", 42);
		renderRouteAtWithRouter("/w/test/admin/access");
		await userEvent.click(
			await screen.findByRole("button", { name: "Request #1 · Applicant" }, ROUTE_RENDER_WAIT),
		);
		expect(
			(await screen.findByText("Another administrator must review your request.")).textContent,
		).toContain("Another administrator");
		expect(screen.queryByRole("button", { name: "Approve access" })).toBeNull();
	});
});
