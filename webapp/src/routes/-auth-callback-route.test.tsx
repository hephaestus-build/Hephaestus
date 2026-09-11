import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });

const destination = "/about?source=invite#team";
const callback = `/auth/callback?returnTo=${encodeURIComponent(destination)}`;

describe("sign-in callback", () => {
	it("resumes the full destination after the session is resolved", async () => {
		const { router } = renderRouteAtWithRouter(callback);
		await screen.findByRole("heading", { name: "About Hephaestus" }, ROUTE_RENDER_WAIT);
		expect(router.state.location.href).toBe(destination);
	});

	it("skips the sign-in form when the session is already authenticated", async () => {
		const { router } = renderRouteAtWithRouter(
			`/login?returnTo=${encodeURIComponent(destination)}`,
		);
		await screen.findByRole("heading", { name: "About Hephaestus" }, ROUTE_RENDER_WAIT);
		expect(router.state.location.href).toBe(destination);
	});

	it("preserves the destination when the session cannot be verified", async () => {
		server.use(http.get("*/user", () => HttpResponse.error()));
		const { router } = renderRouteAtWithRouter(callback);
		await screen.findByRole("button", { name: "Continue with GitHub" }, ROUTE_RENDER_WAIT);
		expect(router.state.location.pathname).toBe("/login");
		expect(router.state.location.search).toMatchObject({ returnTo: destination });
	});

	it("lets the user leave while the session request is still pending", async () => {
		let release = () => {};
		const pending = new Promise<void>((resolve) => {
			release = resolve;
		});
		server.use(
			http.get("*/user", async () => {
				await pending;
				return new HttpResponse(null, { status: 401 });
			}),
		);
		try {
			const { router } = renderRouteAtWithRouter(callback);
			await userEvent.click(
				await screen.findByRole("link", { name: "Back to sign in" }, ROUTE_RENDER_WAIT),
			);
			await screen.findByRole("button", { name: "Continue with GitHub" }, ROUTE_RENDER_WAIT);
			expect(router.state.location.pathname).toBe("/login");
			expect(router.state.location.search).toMatchObject({ returnTo: destination });
		} finally {
			release();
		}
	});
});
