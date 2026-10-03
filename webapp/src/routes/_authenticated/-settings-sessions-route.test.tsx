import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import { sessions } from "@/mocks/fixtures/auth";
import { server } from "@/mocks/server";
import { deferred } from "@/test/async";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });

async function sessionsRegion(): Promise<HTMLElement> {
	return screen.findByRole("region", { name: "Active Sessions" }, ROUTE_RENDER_WAIT);
}

describe("active sessions on the settings page", () => {
	it("lists every session the server returns", async () => {
		renderRouteAt("/settings");
		const region = await sessionsRegion();

		await within(region).findByRole("listitem", { name: "Chrome 124 on macOS" }, ROUTE_RENDER_WAIT);
		expect(within(region).getAllByRole("listitem")).toHaveLength(sessions.length);
	});

	it("revokes one session and reads the list again", async () => {
		const revoked: string[] = [];
		server.use(
			http.get("*/user/sessions", () => HttpResponse.json(sessions), { once: true }),
			http.get("*/user/sessions", () =>
				HttpResponse.json(sessions.filter((s) => s.jti !== "sess-other-002")),
			),
			http.delete("*/user/sessions/:jti", ({ params }) => {
				revoked.push(String(params.jti));
				return new HttpResponse(null, { status: 204 });
			}),
		);
		renderRouteAt("/settings");
		const region = await sessionsRegion();
		const row = await within(region).findByRole(
			"listitem",
			{ name: "Firefox 126 on Ubuntu" },
			ROUTE_RENDER_WAIT,
		);

		fireEvent.click(within(row).getByRole("button", { name: "Revoke this session" }));

		await waitFor(() =>
			expect(within(region).queryByRole("listitem", { name: "Firefox 126 on Ubuntu" })).toBeNull(),
		);
		expect(revoked).toStrictEqual(["sess-other-002"]);
		expect(within(region).getAllByRole("listitem")).toHaveLength(sessions.length - 1);
	});

	it("holds only the revoked row while the request is in flight", async () => {
		const deletion = deferred();
		server.use(
			http.delete("*/user/sessions/:jti", async () => {
				await deletion.promise;
				return new HttpResponse(null, { status: 204 });
			}),
		);
		renderRouteAt("/settings");
		const region = await sessionsRegion();
		const clicked = within(
			await within(region).findByRole(
				"listitem",
				{ name: "Firefox 126 on Ubuntu" },
				ROUTE_RENDER_WAIT,
			),
		).getByRole<HTMLButtonElement>("button", { name: "Revoke this session" });
		const other = within(
			within(region).getByRole("listitem", { name: "Mobile Safari on iOS 18" }),
		).getByRole<HTMLButtonElement>("button", { name: "Revoke this session" });

		fireEvent.click(clicked);

		await waitFor(() => expect(clicked.disabled).toBe(true));
		expect(other.disabled).toBe(false);
		deletion.resolve();
	});

	it("signs out every other session and reads the list again", async () => {
		let signedOutOthers = false;
		server.use(
			http.get("*/user/sessions", () => HttpResponse.json(sessions), { once: true }),
			http.get("*/user/sessions", () =>
				HttpResponse.json(sessions.filter((s) => s.current === true)),
			),
			http.delete("*/user/sessions", () => {
				signedOutOthers = true;
				return new HttpResponse(null, { status: 204 });
			}),
		);
		renderRouteAt("/settings");
		const region = await sessionsRegion();

		fireEvent.click(
			await within(region).findByRole(
				"button",
				{ name: "Sign out 3 other sessions" },
				ROUTE_RENDER_WAIT,
			),
		);
		fireEvent.click(await screen.findByRole("button", { name: "Sign out others" }));

		await waitFor(() => expect(within(region).getAllByRole("listitem")).toHaveLength(1));
		expect(signedOutOthers).toBe(true);
	});

	it("offers a retry when the list fails to load, and the retry reads it again", async () => {
		server.use(
			http.get("*/user/sessions", () => new HttpResponse(null, { status: 500 }), { once: true }),
		);
		renderRouteAt("/settings");
		const region = await sessionsRegion();
		const alert = await within(region).findByRole("alert", {}, ROUTE_RENDER_WAIT);
		expect(alert.textContent).toContain("Could not load sessions");

		fireEvent.click(within(alert).getByRole("button", { name: "Retry" }));

		await within(region).findByRole("listitem", { name: "Chrome 124 on macOS" });
	});
});
