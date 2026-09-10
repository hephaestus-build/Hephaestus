import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

describe("organizational account settings", () => {
	it("offers SCM linking without requesting SCM preferences for an OIDC-only account", async () => {
		let preferenceReads = 0;
		server.use(
			http.get("*/user/identities", () =>
				HttpResponse.json([
					{
						id: 17,
						providerType: "OIDC",
						providerName: "Organization",
						providerId: 9,
						subject: "opaque-subject",
						displayName: "Organization member",
						connectedAt: "2026-09-01T12:00:00Z",
					},
				]),
			),
			http.get("*/user/settings", () => {
				preferenceReads++;
				return HttpResponse.json({ title: "No SCM identity" }, { status: 404 });
			}),
		);
		renderRouteAt("/settings");
		await screen.findByText(/Connect a GitHub or GitLab account below/, {}, ROUTE_RENDER_WAIT);
		expect(screen.getByRole("region", { name: "Account identity" })).not.toBeNull();
		expect(screen.queryByText(/couldn't load your practice feedback preferences/)).toBeNull();
		expect(preferenceReads).toBe(0);
	});
	it("joins only the current account's offer and refreshes available workspace navigation", async () => {
		const user = userEvent.setup();
		let joined = false;
		let workspaceReads = 0;
		let workspaces: {
			id: number;
			workspaceSlug: string;
			displayName: string;
			status: string;
			providerType: string;
		}[] = [];
		const offer = {
			workspaceId: 23,
			slug: "engineering",
			displayName: "Engineering",
			joined: false,
			suspended: false,
		};
		server.use(
			http.get("*/user/workspace-access", () => HttpResponse.json([{ ...offer, joined }])),
			http.get("*/workspaces", () => {
				workspaceReads++;
				return HttpResponse.json(workspaces);
			}),
			http.post("*/user/workspace-access/23", async ({ request }) => {
				expect(await request.text()).toBe("");
				joined = true;
				workspaces = [
					{
						id: 23,
						workspaceSlug: "engineering",
						displayName: "Engineering",
						status: "ACTIVE",
						providerType: "GITHUB",
					},
				];
				return HttpResponse.json({ ...offer, joined });
			}),
		);
		renderRouteAt("/settings");
		await user.click(
			await screen.findByRole("button", { name: "Join Engineering" }, ROUTE_RENDER_WAIT),
		);
		const open = await screen.findByRole("link", { name: "Open workspace" });
		expect(open.getAttribute("href")).toBe("/w/engineering");
		await waitFor(() => expect(workspaceReads).toBeGreaterThan(1));
		expect(joined).toBe(true);
	});

	it("removes an expired offer after the server refuses a join rather than claiming success", async () => {
		const user = userEvent.setup();
		let offers = [
			{
				workspaceId: 23,
				slug: "engineering",
				displayName: "Engineering",
				joined: false,
				suspended: false,
			},
		];
		server.use(
			http.get("*/user/workspace-access", () => HttpResponse.json(offers)),
			http.post("*/user/workspace-access/23", () => {
				offers = [];
				return HttpResponse.json({ status: 404, detail: "Offer is unavailable" }, { status: 404 });
			}),
		);
		renderRouteAt("/settings");
		await user.click(
			await screen.findByRole("button", { name: "Join Engineering" }, ROUTE_RENDER_WAIT),
		);
		await screen.findByText(/No current directory offers/);
		expect(screen.queryByRole("link", { name: "Open workspace" })).toBeNull();
	});
});
