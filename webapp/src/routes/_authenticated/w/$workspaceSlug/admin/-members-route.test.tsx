import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

vi.setConfig({ testTimeout: 15_000 });

const WORKSPACE = {
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

function mockMembersRoute() {
	server.use(
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "OWNER", accountId: 1, scmUserLogin: "ada", displayName: "Ada" }),
		),
		http.get("*/workspaces", () => HttpResponse.json([WORKSPACE])),
		http.get("*/workspaces/:workspaceSlug", () => HttpResponse.json(WORKSPACE)),
		http.get("*/workspaces/:workspaceSlug/connections/catalog", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/members", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/teams", () => HttpResponse.json([])),
	);
}

describe("workspace members route", () => {
	it("grants, suspends and explicitly restores access using the account ID", async () => {
		mockMembersRoute();
		const user = userEvent.setup();
		const member = {
			accountId: 73,
			displayName: "Organization member",
			role: "MEMBER",
			source: "MANUAL",
			suspended: false,
		};
		let members: (typeof member)[] = [];
		let assignments = 0;
		server.use(
			http.get("*/workspaces/acme/members", () => HttpResponse.json(members)),
			http.post("*/workspaces/acme/members/assign", async ({ request }) => {
				expect(await request.json()).toStrictEqual({ accountId: 73, role: "MEMBER" });
				assignments++;
				members = [member];
				member.suspended = false;
				return HttpResponse.json(member);
			}),
			http.delete("*/workspaces/acme/members/73", () => {
				member.suspended = true;
				return new HttpResponse(null, { status: 204 });
			}),
		);
		renderRouteAtWithRouter("/w/acme/admin/members");
		await user.click(await screen.findByRole("button", { name: "Add member" }, ROUTE_RENDER_WAIT));
		let dialog = await screen.findByRole("dialog");
		await user.type(within(dialog).getByLabelText("Account ID"), "73");
		await user.click(within(dialog).getByRole("button", { name: "Save access" }));
		await screen.findByText("Organization member");
		await user.click(
			screen.getByRole("button", { name: "Suspend access for Organization member" }),
		);
		const confirmation = await screen.findByRole("alertdialog");
		await user.click(within(confirmation).getByRole("button", { name: "Suspend access" }));
		await screen.findByText("Suspended");
		await user.click(screen.getByRole("button", { name: "Edit access for Organization member" }));
		dialog = await screen.findByRole("dialog");
		await user.click(within(dialog).getByRole("button", { name: "Restore access" }));
		await waitFor(() => expect(screen.queryByText("Suspended")).toBeNull());
		expect(assignments).toBe(2);
	});

	it("lets an instance administrator appoint the first owner but does not keep that authority", async () => {
		mockMembersRoute();
		const user = userEvent.setup();
		const owner = {
			accountId: 73,
			displayName: "Initial owner",
			role: "OWNER",
			source: "MANUAL",
			suspended: false,
		};
		let members: (typeof owner)[] = [];
		server.use(
			http.get("*/workspaces/acme/members/me", () =>
				HttpResponse.json({
					accountId: 42,
					displayName: "Instance administrator",
					role: "ADMIN",
					suspended: false,
				}),
			),
			http.get("*/workspaces/acme/members", () => HttpResponse.json(members)),
			http.post("*/workspaces/acme/members/assign", async ({ request }) => {
				expect(await request.json()).toStrictEqual({ accountId: 73, role: "OWNER" });
				members = [owner];
				return HttpResponse.json(owner);
			}),
		);
		renderRouteAtWithRouter("/w/acme/admin/members");
		await screen.findByText(/assign its first owner/, {}, ROUTE_RENDER_WAIT);
		await user.click(screen.getByRole("button", { name: "Add member" }));
		const dialog = await screen.findByRole("dialog");
		await user.type(within(dialog).getByLabelText("Account ID"), "73");
		await user.click(within(dialog).getByRole("combobox", { name: "Workspace role" }));
		await user.click(await screen.findByRole("option", { name: "Owner" }));
		await user.click(within(dialog).getByRole("button", { name: "Save access" }));
		await screen.findByText("Initial owner");
		await waitFor(() => expect(screen.queryByText(/assign its first owner/)).toBeNull());
		expect(screen.queryByRole("button", { name: "Edit access for Initial owner" })).toBeNull();
	});

	it("does not trap sidebar navigation", async () => {
		mockMembersRoute();
		const { router } = renderRouteAtWithRouter("/w/acme/admin/members");

		await screen.findByRole("heading", { name: "Members" }, ROUTE_RENDER_WAIT);
		fireEvent.click(await screen.findByRole("link", { name: "Settings" }));

		await waitFor(() => expect(router.state.location.pathname).toBe("/w/acme/admin/settings"));
	});
});
