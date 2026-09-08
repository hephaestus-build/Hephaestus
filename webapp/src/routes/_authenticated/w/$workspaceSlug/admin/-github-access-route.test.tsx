import { act, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";
import type { GitHubAccessTarget } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt, renderRouteAtWithRouter } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });
const target: Wire<GitHubAccessTarget> = {
	id: 1,
	connectionId: 20,
	organization: "example-org",
	installationId: 500,
	status: "DRAFT",
	paused: false,
	authorityHeld: true,
	authorized: true,
	configurationVersion: 2,
	draftGroupIds: ["engineering"],
	approvedGroupIds: [],
	members: [],
	actions: [],
	preview: {
		capturedAt: "2026-09-08T12:00:00Z",
		eligiblePeople: 1,
		awaitingIdentity: 0,
		unlinkedInvitations: 0,
		inventory: [],
	},
};
function fixture(role: "OWNER" | "ADMIN") {
	const workspace = {
		id: 1,
		workspaceSlug: "acme",
		displayName: "Acme",
		providerType: "GITHUB",
		status: "ACTIVE",
		leaguesEnabled: false,
		leaderboardEnabled: false,
		practicesEnabled: true,
		mentorEnabled: false,
		progressionEnabled: false,
	};
	server.use(
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role, accountId: 1, displayName: "Owner" }),
		),
		http.get("*/workspaces", () => HttpResponse.json([workspace])),
		http.get("*/workspaces/:workspaceSlug", () => HttpResponse.json(workspace)),
		http.get("*/workspaces/:workspaceSlug/connections/catalog", () => HttpResponse.json([])),
		http.get("*/workspaces/acme/directory-access", () =>
			HttpResponse.json({ policy: { approvedGroupIds: ["engineering"] } }),
		),
		http.get("*/workspaces/acme/github-access", () =>
			HttpResponse.json({ configured: true, targets: [target] }),
		),
		http.get("*/workspaces/acme/connections/20/sync", () =>
			HttpResponse.json({ resourceCounts: { total: 0, errored: 0, stale: 0 } }),
		),
	);
	return workspace;
}

describe("workspace GitHub access route", () => {
	it("does not carry a private approval capability into another workspace", async () => {
		const workspace = fixture("OWNER");
		const capability = "a".repeat(43);
		server.use(
			http.get("*/workspaces", () =>
				HttpResponse.json([workspace, { ...workspace, id: 2, workspaceSlug: "other" }]),
			),
			http.post("*/workspaces/acme/github-access/1/handoffs", async ({ request }) => {
				expect(await request.json()).toStrictEqual({ installationId: 500 });
				return HttpResponse.json({ target, token: capability });
			}),
			http.get("*/workspaces/other/github-access", () =>
				HttpResponse.json({ configured: true, targets: [] }),
			),
			http.get("*/workspaces/other/directory-access", () =>
				HttpResponse.json({ policy: { approvedGroupIds: ["engineering"] } }),
			),
		);
		const { router } = renderRouteAtWithRouter("/w/acme/admin/github-access");
		const user = userEvent.setup();
		await user.click(
			await screen.findByRole("button", { name: "New approval link" }, ROUTE_RENDER_WAIT),
		);
		const approval = await screen.findByRole<HTMLInputElement>("textbox", {
			name: "Approval link",
		});
		expect(approval.value).toBe(`${window.location.origin}/github-access-approval#${capability}`);
		await act(async () => {
			await router.navigate({
				to: "/w/$workspaceSlug/admin/github-access",
				params: { workspaceSlug: "other" },
			});
		});
		await screen.findByRole("heading", { name: "No GitHub targets yet" }, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("textbox", { name: "Approval link" })).toBeNull();
	});

	it("approves only the exact displayed preview and target", async () => {
		fixture("OWNER");
		let approvals = 0;
		server.use(
			http.post("*/workspaces/acme/github-access/1/approvals", async ({ request }) => {
				expect(await request.json()).toStrictEqual({
					configurationVersion: 2,
					previewCapturedAt: "2026-09-08T12:00:00.000Z",
				});
				approvals++;
				return HttpResponse.json({ ...target, status: "ACTIVE" });
			}),
		);
		renderRouteAt("/w/acme/admin/github-access");
		const user = userEvent.setup();
		await user.click(
			await screen.findByRole("button", { name: "Approve this exact preview" }, ROUTE_RENDER_WAIT),
		);
		await waitFor(() => expect(approvals).toBe(1));
	});
	it("keeps administrator operations separate from owner-only policy and directory reads", async () => {
		fixture("ADMIN");
		let directoryReads = 0;
		let reconciliations = 0;
		server.use(
			http.get("*/workspaces/acme/directory-access", () => {
				directoryReads++;
				return new HttpResponse(null, { status: 403 });
			}),
			http.post("*/workspaces/acme/github-access/1/reconciliations", () => {
				reconciliations++;
				return HttpResponse.json(
					{ id: 5, type: "RECONCILIATION", status: "RUNNING", createdAt: "2026-09-08T12:00:00Z" },
					{ status: 202 },
				);
			}),
		);
		renderRouteAt("/w/acme/admin/github-access");
		const user = userEvent.setup();
		await user.click(
			await screen.findByRole("button", { name: "Reconcile / retry" }, ROUTE_RENDER_WAIT),
		);
		await waitFor(() => expect(reconciliations).toBe(1));
		expect(directoryReads).toBe(0);
		expect(screen.queryByRole("button", { name: "Approve this exact preview" })).toBeNull();
		expect(screen.queryByRole("button", { name: "Add GitHub target" })).toBeNull();
	});
});
