import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeAll, beforeEach, describe, expect, it } from "vitest";

import type { SupportShare } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, loadRouteCode, renderRouteAt } from "@/test/router-harness";

beforeAll(async () => loadRouteCode("/settings"), ROUTE_RENDER_WAIT.timeout);

// The page is not workspace-scoped, so the reader belongs to no workspace here.
beforeEach(() => {
	server.use(http.get("*/workspaces", () => HttpResponse.json([])));
});

const active = {
	id: "0198f5a2-7c4e-7a10-8f3b-2d6c1e9a4b90",
	turnId: "0198f5a2-7c4e-7a10-8f3b-2d6c1e9a4b70",
	workspaceSlug: "acme",
	recipient: { accountId: "7", displayName: "Ada Lovelace" },
	includesText: true,
	state: "ACTIVE",
	createdAt: "2026-09-11T09:00:00.000Z",
	expiresAt: "2026-09-18T09:00:00.000Z",
	readCount: 1,
	lastReadAt: "2026-09-11T10:00:00.000Z",
} satisfies Wire<SupportShare>;

describe("support shares on the settings page", () => {
	it("revokes an active share at once, and keeps it in the list as revoked", async () => {
		const revoked: string[] = [];
		let listed: Wire<SupportShare> = active;
		server.use(
			http.get("*/user/support-shares", () => HttpResponse.json([listed])),
			http.delete("*/user/support-shares/:shareId", ({ params }) => {
				revoked.push(String(params.shareId));
				listed = { ...active, state: "REVOKED", revokedAt: "2026-09-11T11:00:00.000Z" };
				return HttpResponse.json(listed);
			}),
		);

		renderRouteAt("/settings");
		const region = await screen.findByRole("region", { name: "Support shares" }, ROUTE_RENDER_WAIT);
		await within(region).findByText(/Read once/u, undefined, ROUTE_RENDER_WAIT);
		await userEvent.click(within(region).getByRole("button", { name: "Revoke" }));

		await waitFor(() => expect(revoked).toStrictEqual([active.id]));
		await within(region).findByText("Revoked");
		expect(within(region).queryByRole("button", { name: "Revoke" })).toBeNull();
	});

	it("says so when nothing is shared", async () => {
		renderRouteAt("/settings");
		const region = await screen.findByRole("region", { name: "Support shares" }, ROUTE_RENDER_WAIT);
		await within(region).findByText(
			/You have not shared a question/u,
			undefined,
			ROUTE_RENDER_WAIT,
		);
	});
});
