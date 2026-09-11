import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

// Mounting the real route pulls in the whole admin layout and its lazy modules.
vi.setConfig({ testTimeout: 20_000 });

const stepUpRefusal = () =>
	HttpResponse.json(
		{
			status: 403,
			title: "Confirm access",
			detail: "This action requires a recent sign-in.",
			code: "step_up_required",
			maxAgeSeconds: 300,
		},
		{ status: 403 },
	);

/** Opens one row's action menu and picks an item from it. */
async function chooseRowAction(rowName: string, action: string) {
	const user = userEvent.setup();
	await user.click(await screen.findByRole("button", { name: rowName }, ROUTE_RENDER_WAIT));
	await user.click(await screen.findByRole("menuitem", { name: action }));
	return user;
}

describe("instance users route", () => {
	it("offers only the sign-in providers this account is linked to when a role change is refused", async () => {
		let roleChanges = 0;
		server.use(
			http.patch("*/admin/users/:id", () => {
				roleChanges += 1;
				return stepUpRefusal();
			}),
			// The instance also offers GitHub, but this account has only ever signed in with GitLab.
			http.get("*/user/identities", () =>
				HttpResponse.json([{ id: 2, providerType: "GITLAB", username: "ada" }]),
			),
		);

		renderRouteAt("/admin/users");
		const user = await chooseRowAction("Actions for Ada Lovelace", "Change role");
		await user.click(await screen.findByRole("button", { name: "Grant admin" }));

		const dialog = await screen.findByRole("dialog", { name: "Confirm access" });
		expect(dialog.textContent).toContain("sign-in from the last 5 minutes");
		expect(dialog.textContent).toContain("not a second factor");
		expect(screen.getByRole("button", { name: "Continue with GitLab" })).not.toBeNull();
		// Signing in with GitHub here would resolve a different account and end this session.
		expect(screen.queryByRole("button", { name: "Continue with GitHub" })).toBeNull();

		// The ask replaces the confirmation it came from rather than stacking on top of it.
		expect(screen.queryByText("Grant application admin?")).toBeNull();

		await user.click(screen.getByRole("button", { name: "Close" }));
		await waitFor(() =>
			expect(screen.queryByRole("dialog", { name: "Confirm access" })).toBeNull(),
		);
		// Dismissing the ask abandons the action; it must not be replayed against the same refusal.
		expect(roleChanges).toBe(1);
	});

	it("asks for a fresh sign-in instead of reporting a force sign-out failure", async () => {
		server.use(http.delete("*/admin/users/:id/sessions", () => stepUpRefusal()));

		renderRouteAt("/admin/users");
		const user = await chooseRowAction("Actions for Ada Lovelace", "Force sign-out");
		await user.click(await screen.findByRole("button", { name: "Force sign-out" }));

		await screen.findByRole("dialog", { name: "Confirm access" });
		expect(screen.queryByText(/Couldn't sign the user out/)).toBeNull();
	});

	it("says nothing about a recent sign-in when the refusal is an ordinary one", async () => {
		server.use(
			http.patch("*/admin/users/:id", () =>
				HttpResponse.json(
					{ status: 409, detail: "The last application admin cannot be demoted." },
					{ status: 409 },
				),
			),
		);

		renderRouteAt("/admin/users");
		const user = await chooseRowAction("Actions for Ada Lovelace", "Change role");
		await user.click(await screen.findByRole("button", { name: "Grant admin" }));

		const refusal = await screen.findByRole("alert");
		expect(refusal.textContent).toBe("The last application admin cannot be demoted.");
		expect(screen.queryByRole("dialog", { name: "Confirm access" })).toBeNull();
	});
});
