import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it } from "vitest";

import type { ProviderResourceRepair } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";

import { currentUser } from "@/mocks/fixtures/auth";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { WorkspaceProviderRepairs } from "@/routes/_authenticated/-provider-repairs";
import { ROUTE_RENDER_WAIT, renderRouteAt, testQueryClient } from "@/test/router-harness";

const repair: Wire<ProviderResourceRepair> = {
	id: "39bcf165-6694-4669-bb6e-5eeb144233b8",
	identityHash: "a".repeat(64),
	kind: "OUTLINE",
	origin: "https://outline.example.test",
	resourceType: "SUBSCRIPTION",
	parentId: "",
	resourceId: "old-subscription",
	state: "CONFIRMATION_REQUIRED",
	replacementInUse: false,
	createdAt: "2026-11-13T00:00:00Z",
	owners: [],
};
const page = (content: Wire<ProviderResourceRepair>[]) => ({
	content,
	page: 0,
	size: 25,
	totalElements: content.length,
	totalPages: content.length === 0 ? 0 : 1,
});

async function confirm() {
	const user = userEvent.setup();
	await user.click(
		await screen.findByRole("button", { name: "Confirm removal" }, ROUTE_RENDER_WAIT),
	);
	const dialog = screen.getByRole("dialog");
	expect(
		within(dialog).getByRole<HTMLButtonElement>("button", { name: "Release block" }).disabled,
	).toBe(true);
	await user.click(within(dialog).getByRole("checkbox"));
	await user.click(within(dialog).getByRole("button", { name: "Release block" }));
}

beforeEach(() => {
	server.use(
		http.get("*/workspaces", () => HttpResponse.json([])),
		http.get("*/admin/settings", () =>
			HttpResponse.json({ silentModeEngaged: false, etag: '"0"' }),
		),
	);
});

describe("provider removal routes", () => {
	it("releases an orphan by its exact identity and reloads the instance list", async () => {
		let visible = [repair];
		let body: unknown;
		let target: unknown;
		server.use(
			http.get("*/admin/provider-resource-repairs", () => HttpResponse.json(page(visible))),
			http.post(
				"*/admin/provider-resource-repairs/:repairId/release",
				async ({ params, request }) => {
					target = params.repairId;
					body = await request.json();
					visible = [];
					return new HttpResponse(null, { status: 204 });
				},
			),
		);
		renderRouteAt("/admin/provider-removals");
		await confirm();
		await screen.findByText("No pending provider removals.");
		expect(target).toBe(repair.id);
		expect(body).toStrictEqual({ confirmedGone: true, identityHash: repair.identityHash });
		expect(screen.queryByRole("dialog")).toBeNull();
	});

	it("binds a workspace release to the connection that displayed the repair", async () => {
		let visible = [repair];
		let submitted: unknown;
		server.use(
			http.get("*/workspaces/own/connections/7/provider-repairs", () =>
				HttpResponse.json(page(visible)),
			),
			http.post(
				"*/workspaces/:workspaceSlug/connections/:connectionId/provider-repairs/:repairId/release",
				async ({ params, request }) => {
					submitted = {
						workspaceSlug: params.workspaceSlug,
						connectionId: params.connectionId,
						repairId: params.repairId,
						body: await request.json(),
					};
					visible = [];
					return new HttpResponse(null, { status: 204 });
				},
			),
		);
		render(
			<QueryClientProvider client={testQueryClient()}>
				<WorkspaceProviderRepairs workspaceSlug="own" connectionId={7} />
			</QueryClientProvider>,
		);
		await confirm();
		await screen.findByText("No pending provider removals.");
		expect(submitted).toStrictEqual({
			workspaceSlug: "own",
			connectionId: "7",
			repairId: repair.id,
			body: { confirmedGone: true, identityHash: repair.identityHash },
		});
	});

	it("offers no release to a workspace-only admin", async () => {
		server.use(
			http.get("*/user", () => HttpResponse.json({ ...currentUser, appRole: "USER" })),
			http.get("*/workspaces/own/connections/7/provider-repairs", () =>
				HttpResponse.json(page([repair])),
			),
		);
		render(
			<QueryClientProvider client={testQueryClient()}>
				<WorkspaceProviderRepairs workspaceSlug="own" connectionId={7} />
			</QueryClientProvider>,
		);
		await screen.findByText("old-subscription", { exact: false });
		expect(screen.queryByRole("button", { name: "Confirm removal" })).toBeNull();
	});

	it("keeps a refused release open and does not report success", async () => {
		server.use(
			http.get("*/admin/provider-resource-repairs", () => HttpResponse.json(page([repair]))),
			http.post("*/admin/provider-resource-repairs/:repairId/release", () =>
				HttpResponse.json({ status: 409, detail: "The identity changed." }, { status: 409 }),
			),
		);
		renderRouteAt("/admin/provider-removals");
		await confirm();
		await screen.findByText("We could not release this block");
		expect(screen.getByRole("dialog")).not.toBeNull();
		expect(screen.queryByText("No pending provider removals.")).toBeNull();
	});

	it("follows an old connection's repair link and releases it while a replacement is active", async () => {
		const listed = workspaceListItem("own");
		let visible = [{ ...repair, owners: [{ connectionId: 7, workspaceSlug: "own" }] }];
		let releasedConnection: unknown;
		server.use(
			http.get("*/admin/provider-resource-repairs", () => HttpResponse.json(page(visible))),
			http.get("*/workspaces", () => HttpResponse.json([listed])),
			http.get("*/workspaces/own", () => HttpResponse.json({ ...listed, kind: "GITHUB" })),
			http.get("*/workspaces/own/members/me", () => HttpResponse.json(ownMembership("ADMIN"))),
			http.get("*/workspaces/own/connections", () =>
				HttpResponse.json([
					{ id: 7, kind: "OUTLINE", state: "UNINSTALLED" },
					{ id: 8, kind: "OUTLINE", state: "ACTIVE" },
				]),
			),
			http.get("*/workspaces/own/connections/catalog", () => HttpResponse.json([])),
			http.get("*/workspaces/own/connections/8/sync", () =>
				HttpResponse.json({
					connectionId: 8,
					connectionState: "ACTIVE",
					kind: "OUTLINE",
					health: "HEALTHY",
					resourceCounts: { total: 0, errored: 0, pending: 0, stale: 0 },
					backfillSupported: true,
				}),
			),
			http.get("*/workspaces/own/connections/8/sync/resources", () => HttpResponse.json([])),
			http.get("*/workspaces/own/connections/8/sync/jobs", () =>
				HttpResponse.json({ content: [], totalPages: 0 }),
			),
			http.get("*/workspaces/own/connections/outline/token", () =>
				HttpResponse.json({ valid: true }),
			),
			http.get("*/workspaces/own/outline/collections", () => HttpResponse.json([])),
			http.get("*/workspaces/own/outline/collections/candidates", () => HttpResponse.json([])),
			http.get("*/workspaces/own/connections/7/provider-repairs", () =>
				HttpResponse.json(page(visible)),
			),
			http.post(
				"*/workspaces/own/connections/:connectionId/provider-repairs/:repairId/release",
				({ params }) => {
					releasedConnection = params.connectionId;
					visible = [];
					return new HttpResponse(null, { status: 204 });
				},
			),
		);
		renderRouteAt("/admin/provider-removals");
		await userEvent.click(
			await screen.findByRole(
				"link",
				{ name: "Open connection administration" },
				ROUTE_RENDER_WAIT,
			),
		);
		await confirm();
		await screen.findByText("No pending provider removals.");
		expect(releasedConnection).toBe("7");
	});

	it("links a surviving owner to connection administration instead of the orphan release", async () => {
		server.use(
			http.get("*/admin/provider-resource-repairs", () =>
				HttpResponse.json(
					page([{ ...repair, owners: [{ connectionId: 7, workspaceSlug: "own" }] }]),
				),
			),
		);
		renderRouteAt("/admin/provider-removals");
		const link = await screen.findByRole(
			"link",
			{ name: "Open connection administration" },
			ROUTE_RENDER_WAIT,
		);
		expect(link.getAttribute("href")).toBe("/w/own/admin/integrations/outline?repairConnection=7");
		expect(screen.queryByRole("button", { name: "Confirm removal" })).toBeNull();
	});
});
