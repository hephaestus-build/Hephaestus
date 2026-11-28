import { act, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { beforeAll, beforeEach, describe, expect, it } from "vitest";

import type { InstanceSettings, SupportShareReceived } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { server } from "@/mocks/server";
import { loadRouteCode, ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

beforeAll(
	async () => loadRouteCode("/admin/support-shares", "/admin/workspaces"),
	ROUTE_RENDER_WAIT.timeout,
);

beforeEach(() => {
	server.use(
		http.get("*/workspaces", () => HttpResponse.json([])),
		http.get("*/admin/workspaces", () => HttpResponse.json([])),
		http.get("*/admin/settings", () =>
			HttpResponse.json({ etag: "1", silentModeEngaged: false } satisfies InstanceSettings),
		),
	);
});

const message = "A message shared with support.";
const share = {
	id: "0198f5a2-7c4e-7a10-8f3b-2d6c1e9a4b90",
	sharedBy: "Katherine Johnson",
	workspaceSlug: "acme",
	includesText: true,
	createdAt: "2026-11-26T09:00:00.000Z",
	expiresAt: "2026-12-03T09:00:00.000Z",
	messages: [
		{
			entryId: "0198f5a2-7c4e-7a10-8f3b-2d6c1e9a4b80",
			author: "QUESTION",
			text: message,
			createdAt: "2026-11-26T09:00:00.000Z",
		},
	],
	textWithheld: false,
} satisfies Wire<SupportShareReceived>;

function openShare() {
	let revoked = false;
	let reads = 0;
	server.use(
		http.get("*/admin/support-shares", () => HttpResponse.json([])),
		http.get(`*/admin/support-shares/${share.id}`, () => {
			reads += 1;
			return revoked
				? HttpResponse.json({ status: 404, title: "Not found" }, { status: 404 })
				: HttpResponse.json(share, { headers: { "Cache-Control": "no-store" } });
		}),
	);
	const { router } = renderRouteAtWithRouter(`/admin/support-shares?shareId=${share.id}`);
	return {
		router,
		get reads() {
			return reads;
		},
		revoke: () => {
			revoked = true;
		},
	};
}

describe("instance support shares", () => {
	it("shows a shared message on the first authorized read", async () => {
		const open = openShare();
		await screen.findByText(message, undefined, ROUTE_RENDER_WAIT);
		expect(open.reads).toBe(1);
	});

	it("checks revocation when the recipient returns after navigating away", async () => {
		const open = openShare();
		await screen.findByText(message, undefined, ROUTE_RENDER_WAIT);
		await act(async () => {
			await open.router.navigate({ to: "/admin/workspaces" });
		});
		open.revoke();
		await act(async () => {
			await open.router.navigate({ to: "/admin/support-shares", search: { shareId: share.id } });
		});

		await waitFor(() => expect(open.reads).toBe(2));
		expect(screen.queryByText(message)).toBeNull();
	});
});
