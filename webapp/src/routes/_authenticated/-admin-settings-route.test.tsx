import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

const settings = (revision: number) => ({
	silentModeEngaged: true,
	etag: `"${revision}"`,
	silentModeReason: "incident",
	silentModeChangedAt: "2026-08-03T08:00:00Z",
	silentModeChangedBy: "operator",
});

describe("instance settings route", () => {
	it("sends If-Match and reloads current state after a stale release", async () => {
		const user = userEvent.setup();
		let reads = 0;
		let writes = 0;
		let ifMatch: string | null = null;
		server.use(
			http.get("*/admin/settings", () => {
				reads += 1;
				return HttpResponse.json(settings(reads));
			}),
			http.patch("*/admin/settings/silent-mode", ({ request }) => {
				writes += 1;
				ifMatch = request.headers.get("If-Match");
				return HttpResponse.json(
					{ status: 412, title: "Instance settings changed" },
					{ status: 412 },
				);
			}),
		);
		renderRouteAt("/admin/settings");

		await user.click(
			await screen.findByRole("button", { name: "Release silent mode…" }, ROUTE_RENDER_WAIT),
		);
		const dialog = await screen.findByRole("alertdialog");
		await user.type(within(dialog).getByLabelText(/Type release to confirm/u), "release");
		await user.click(within(dialog).getByRole("button", { name: "Release silent mode" }));

		await waitFor(() => expect(reads).toBe(2));
		expect(ifMatch).toBe('"1"');
		expect(writes).toBe(1);
		await screen.findByText(/Verify the current state before trying again/u);
		expect(screen.queryByRole("alertdialog")).toBeNull();
	});

	it("keeps release disabled when stale settings cannot be reloaded", async () => {
		const user = userEvent.setup();
		server.use(
			http.get("*/admin/settings", () => HttpResponse.json(settings(1)), { once: true }),
			// The reload the 412 triggers is the one that has to fail.
			http.get("*/admin/settings", () =>
				HttpResponse.json({ title: "Unavailable" }, { status: 503 }),
			),
			http.patch("*/admin/settings/silent-mode", () =>
				HttpResponse.json({ status: 412, title: "Instance settings changed" }, { status: 412 }),
			),
		);
		renderRouteAt("/admin/settings");

		await user.click(
			await screen.findByRole("button", { name: "Release silent mode…" }, ROUTE_RENDER_WAIT),
		);
		const dialog = await screen.findByRole("alertdialog");
		await user.type(within(dialog).getByLabelText(/Type release to confirm/u), "release");
		await user.click(within(dialog).getByRole("button", { name: "Release silent mode" }));

		await screen.findByText("Couldn't verify the current instance settings");
		await within(dialog).findByText(/The current settings could not be verified/u);
		expect(
			within(dialog).getByRole<HTMLButtonElement>("button", { name: "Release silent mode" })
				.disabled,
		).toBe(true);
	});
});
