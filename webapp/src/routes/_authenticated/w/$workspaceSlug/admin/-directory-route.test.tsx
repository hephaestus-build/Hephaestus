import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import type { DirectoryPolicy } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });
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
	achievementsEnabled: false,
	progressionEnabled: false,
};
const source = {
	registrationId: "organization",
	displayName: "Organization",
	issuer: "https://identity.example.com/realms/team",
	groupIds: ["engineering"],
};
const evidence = {
	startedAt: "2026-09-08T12:00:00Z",
	completedAt: "2026-09-08T12:01:00Z",
	fresh: true,
	eligiblePeople: 1,
	awaitingIdentity: 0,
	additions: 1,
	removals: 0,
	groupNames: { engineering: "Engineering" },
};
const draft: Wire<DirectoryPolicy> = {
	connectionId: 9,
	registrationId: source.registrationId,
	issuer: source.issuer,
	configurationVersion: 3,
	status: "DRAFT",
	health: "UNVERIFIED",
	draftGroupIds: ["engineering"],
	approvedGroupIds: [],
	blockers: [],
	members: [],
};
function mockRoute(role: "OWNER" | "ADMIN" = "OWNER") {
	server.use(
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role, accountId: 1, displayName: "Owner" }),
		),
		http.get("*/workspaces", () => HttpResponse.json([workspace])),
		http.get("*/workspaces/:workspaceSlug", () => HttpResponse.json(workspace)),
		http.get("*/workspaces/:workspaceSlug/connections/catalog", () => HttpResponse.json([])),
		http.get("*/workspaces/acme/directory-access/sources", () => HttpResponse.json([source])),
		http.get("*/workspaces/acme/connections/9/sync", () =>
			HttpResponse.json({ resourceCounts: { total: 0, errored: 0, stale: 0 } }),
		),
		http.get("*/workspaces/acme/connections/9/sync/jobs", () =>
			HttpResponse.json({ content: [], totalPages: 0 }),
		),
		http.get("*/workspaces/acme/members", () => HttpResponse.json([])),
	);
}
const job = {
	id: 2,
	type: "INITIAL",
	trigger: "MANUAL",
	status: "SUCCEEDED",
	cancelRequested: false,
	createdAt: "2026-09-08T12:00:00Z",
};

describe("workspace directory route", () => {
	it("saves read credentials, previews without granting and approves the displayed configuration version", async () => {
		mockRoute();
		const user = userEvent.setup();
		let policy: Wire<DirectoryPolicy> | undefined;
		let approvals = 0;
		server.use(
			http.get("*/workspaces/acme/directory-access", () => HttpResponse.json({ policy })),
			http.put("*/workspaces/acme/directory-access", async ({ request }) => {
				expect(await request.json()).toStrictEqual({
					registrationId: "organization",
					groupIds: ["engineering"],
					credentials: { clientId: "readonly", clientSecret: "fixture-only" },
				});
				policy = { ...draft };
				return HttpResponse.json(policy);
			}),
			http.post("*/workspaces/acme/directory-access/previews", () => {
				policy = { ...draft, previewEvidence: evidence, health: "HEALTHY" };
				return HttpResponse.json(job, { status: 202 });
			}),
			http.post("*/workspaces/acme/directory-access/approvals", async ({ request }) => {
				expect(await request.json()).toStrictEqual({ configurationVersion: 3 });
				approvals++;
				policy = {
					...draft,
					status: "ACTIVE",
					health: "HEALTHY",
					approvedEvidence: evidence,
					approvedGroupIds: ["engineering"],
				};
				return HttpResponse.json(policy);
			}),
		);
		renderRouteAt("/w/acme/admin/directory");
		await user.click(
			await screen.findByRole("checkbox", { name: "engineering" }, ROUTE_RENDER_WAIT),
		);
		await user.type(screen.getByLabelText("Read-only directory client ID"), "readonly");
		await user.type(screen.getByLabelText("Directory client secret"), "fixture-only");
		await user.click(screen.getByRole("button", { name: "Save configuration" }));
		await user.click(await screen.findByRole("button", { name: "Preview saved configuration" }));
		const approve = await screen.findByRole<HTMLButtonElement>("button", {
			name: "Approve this preview",
		});
		await waitFor(() => expect(approve.disabled).toBe(false));
		expect(approvals).toBe(0);
		expect(screen.getByLabelText<HTMLInputElement>("Directory client secret").value).toBe("");
		await user.click(approve);
		await screen.findByRole("button", { name: "Pause new access" });
		expect(approvals).toBe(1);
	});

	it("lets administrators inspect and reconcile without fetching owner-only sources", async () => {
		mockRoute("ADMIN");
		let sourceRequests = 0;
		let reads = 0;
		server.use(
			http.get("*/workspaces/acme/directory-access", () =>
				HttpResponse.json({
					policy: { ...draft, status: "ACTIVE", health: "HEALTHY", approvedEvidence: evidence },
				}),
			),
			http.get("*/workspaces/acme/directory-access/sources", () => {
				sourceRequests++;
				return new HttpResponse(null, { status: 403 });
			}),
			http.post("*/workspaces/acme/directory-access/reconciliations", () => {
				reads++;
				return HttpResponse.json({ ...job, type: "RECONCILIATION" }, { status: 202 });
			}),
		);
		renderRouteAt("/w/acme/admin/directory");
		const user = userEvent.setup();
		await user.click(
			await screen.findByRole("button", { name: "Reconcile now" }, ROUTE_RENDER_WAIT),
		);
		await waitFor(() => expect(reads).toBe(1));
		expect(sourceRequests).toBe(0);
		expect(screen.queryByRole("button", { name: "Approve this preview" })).toBeNull();
		expect(screen.queryByRole("button", { name: "Save configuration" })).toBeNull();
	});

	it("keeps failed loads retryable and requires confirmation before ending management", async () => {
		mockRoute();
		const user = userEvent.setup();
		let current: Wire<DirectoryPolicy> = { ...draft, status: "ACTIVE" };
		let ended = false;
		server.use(
			http.get("*/workspaces/acme/directory-access", () =>
				HttpResponse.json({ detail: "Unavailable" }, { status: 503 }),
			),
			http.patch("*/workspaces/acme/directory-access/status", async ({ request }) => {
				expect(await request.json()).toStrictEqual({ status: "ENDED" });
				ended = true;
				current = { ...draft, status: "ENDED" };
				return HttpResponse.json(current);
			}),
		);
		renderRouteAt("/w/acme/admin/directory");
		await screen.findByText("Couldn't load directory access", {}, ROUTE_RENDER_WAIT);
		server.use(
			http.get("*/workspaces/acme/directory-access", () => HttpResponse.json({ policy: current })),
		);
		await user.click(screen.getByRole("button", { name: /retry/i }));
		await user.click(await screen.findByRole("button", { name: "End management" }));
		const dialog = await screen.findByRole("alertdialog");
		expect(ended).toBe(false);
		await user.click(within(dialog).getByRole("button", { name: "End management" }));
		await screen.findByRole("heading", { name: "Start a new policy" });
		expect(ended).toBe(true);
	});
});
