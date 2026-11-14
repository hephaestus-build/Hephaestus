import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeAll, describe, expect, it } from "vitest";

import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, loadRouteCode, renderRouteAt } from "@/test/router-harness";

beforeAll(async () => loadRouteCode("/settings"), ROUTE_RENDER_WAIT.timeout);

/** The reader belongs to Acme and Tutors. Each leave the server receives is recorded. */
function serveWorkspaces() {
	let listed = [
		workspaceListItem("acme", { displayName: "Acme" }),
		workspaceListItem("tutors", { displayName: "Tutors" }),
	];
	const left: string[] = [];
	server.use(
		http.get("*/workspaces", () => HttpResponse.json(listed)),
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json(ownMembership("MEMBER")),
		),
		http.delete("*/workspaces/:workspaceSlug/members/me", ({ params }) => {
			const slug = String(params.workspaceSlug);
			left.push(slug);
			listed = listed.filter((workspace) => workspace.workspaceSlug !== slug);
			return new HttpResponse(null, { status: 204 });
		}),
	);
	return left;
}

async function workspacesRegion(): Promise<HTMLElement> {
	return screen.findByRole("region", { name: "Workspaces" }, ROUTE_RENDER_WAIT);
}

describe("leaving a workspace from User settings", () => {
	it("leaves the confirmed workspace and lists the others", async () => {
		const left = serveWorkspaces();
		renderRouteAt("/settings");
		const region = await workspacesRegion();

		await userEvent.click(
			await within(region).findByRole("button", { name: "Leave Tutors" }, ROUTE_RENDER_WAIT),
		);
		const dialog = within(await screen.findByRole("alertdialog"));
		await userEvent.click(dialog.getByRole("button", { name: "Leave workspace" }));

		await waitFor(() => {
			expect(within(region).queryByRole("button", { name: "Leave Tutors" })).toBeNull();
		});
		expect(left).toStrictEqual(["tutors"]);
		within(region).getByRole("button", { name: "Leave Acme" });
	});

	it("keeps the confirmation open with the reason when the last owner cannot leave", async () => {
		serveWorkspaces();
		server.use(
			http.delete("*/workspaces/acme/members/me", () =>
				HttpResponse.json(
					{
						status: 409,
						title: "Workspace owner required",
						detail:
							"You are the last owner of acme. Make another member an owner, or delete the workspace.",
					},
					{ status: 409 },
				),
			),
		);
		renderRouteAt("/settings");
		const region = await workspacesRegion();

		await userEvent.click(
			await within(region).findByRole("button", { name: "Leave Acme" }, ROUTE_RENDER_WAIT),
		);
		const dialog = within(await screen.findByRole("alertdialog"));
		await userEvent.click(dialog.getByRole("button", { name: "Leave workspace" }));

		await dialog.findByText(
			"You are the last owner of acme. Make another member an owner, or delete the workspace.",
		);
		// The confirmation stays open over the page, which still lists the workspace.
		screen.getByRole("alertdialog");
		within(region).getByRole("button", { name: "Leave Acme", hidden: true });
	});
});
