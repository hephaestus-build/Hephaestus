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
			http.get("*/admin/settings", () => HttpResponse.json(settings(++reads))),
			http.patch("*/admin/settings/silent-mode", ({ request }) => {
				writes++;
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
		await user.type(within(dialog).getByLabelText(/Type release to confirm/), "release");
		await user.click(within(dialog).getByRole("button", { name: "Release silent mode" }));

		await waitFor(() => expect(reads).toBe(2));
		expect(ifMatch).toBe('"1"');
		expect(writes).toBe(1);
		await screen.findByText(/Verify the current state before trying again/);
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
		await user.type(within(dialog).getByLabelText(/Type release to confirm/), "release");
		await user.click(within(dialog).getByRole("button", { name: "Release silent mode" }));

		await screen.findByText("Couldn't verify the current instance settings");
		await within(dialog).findByText(/The current settings could not be verified/);
		expect(
			within(dialog).getByRole<HTMLButtonElement>("button", { name: "Release silent mode" })
				.disabled,
		).toBe(true);
	});

	it.each([
		{ recipient: "  ops@example.org ", expectedBody: { to: "ops@example.org" } },
		{ recipient: " ", expectedBody: {} },
	])(
		"posts $expectedBody to the test-email endpoint and shows relay acceptance",
		async ({ recipient, expectedBody }) => {
			const user = userEvent.setup();
			let body: unknown;
			server.use(
				http.get("*/admin/settings", () => HttpResponse.json(settings(1))),
				http.post("*/admin/email/test", async ({ request }) => {
					body = await request.json();
					return HttpResponse.json({
						outcome: "SENT",
						to: "ops@example.org",
						messageId: "<abc@hephaestus.example>",
					});
				}),
			);
			renderRouteAt("/admin/settings");

			const input = await screen.findByRole("textbox", { name: "Recipient" }, ROUTE_RENDER_WAIT);
			await user.type(input, recipient);
			await user.click(screen.getByRole("button", { name: "Send test email" }));

			await screen.findByText("Accepted by relay");
			expect(body).toStrictEqual(expectedBody);
			await screen.findByText("<abc@hephaestus.example>");
		},
	);

	it("names the stop a withheld test email hit", async () => {
		const user = userEvent.setup();
		server.use(
			http.get("*/admin/settings", () => HttpResponse.json(settings(1))),
			http.post("*/admin/email/test", () => HttpResponse.json({ outcome: "SILENT_MODE" })),
		);
		renderRouteAt("/admin/settings");

		await user.click(
			await screen.findByRole("button", { name: "Send test email" }, ROUTE_RENDER_WAIT),
		);

		await screen.findAllByText("Withheld by silent mode");
		expect(screen.queryByText("Accepted by relay")).toBeNull();
	});

	it("does not leave a previous success visible after the next request fails", async () => {
		const user = userEvent.setup();
		server.use(
			http.get("*/admin/settings", () => HttpResponse.json(settings(1))),
			http.post(
				"*/admin/email/test",
				() => HttpResponse.json({ outcome: "SENT", to: "ops@example.org" }),
				{ once: true },
			),
			http.post("*/admin/email/test", () =>
				HttpResponse.json({ title: "Unavailable" }, { status: 503 }),
			),
		);
		renderRouteAt("/admin/settings");

		const send = await screen.findByRole("button", { name: "Send test email" }, ROUTE_RENDER_WAIT);
		await user.click(send);
		await screen.findByText("Accepted by relay");
		await user.click(send);

		await screen.findByText("Unavailable");
		expect(screen.queryByText("Accepted by relay")).toBeNull();
	});

	it("can test email when the instance settings cannot be loaded", async () => {
		const user = userEvent.setup();
		server.use(
			http.get("*/admin/settings", () =>
				HttpResponse.json({ title: "Unavailable" }, { status: 503 }),
			),
			http.post("*/admin/email/test", () =>
				HttpResponse.json({ outcome: "SENT", to: "ops@example.org" }),
			),
		);
		renderRouteAt("/admin/settings");

		await screen.findByText("Couldn't load instance settings", undefined, ROUTE_RENDER_WAIT);
		await user.click(screen.getByRole("button", { name: "Send test email" }));

		await screen.findByText("Accepted by relay");
		expect(screen.queryByRole("button", { name: "Release silent mode…" })).toBeNull();
	});
});
