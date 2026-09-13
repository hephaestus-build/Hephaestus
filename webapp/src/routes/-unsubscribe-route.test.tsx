import { act, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import { unauthenticatedUser } from "@/mocks/handlers";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt, renderRouteAtWithRouter } from "@/test/router-harness";

const token = "069cc67f-750f-44b8-8808-57c23a2db046";

describe("anonymous email unsubscribe confirmation", () => {
	it("does nothing on GET and sends an anonymous one-click form only after confirmation", async () => {
		const user = userEvent.setup();
		const requests: Request[] = [];
		server.use(
			unauthenticatedUser,
			http.post("*/notifications/unsubscribe/:token", ({ request }) => {
				requests.push(request);
				return new HttpResponse(null, { status: 204 });
			}),
		);
		renderRouteAt(`/unsubscribe?token=${token}`);
		const confirm = await screen.findByRole("button", { name: "Unsubscribe" }, ROUTE_RENDER_WAIT);
		expect(requests).toHaveLength(0);
		expect(screen.queryByText(token)).toBeNull();
		await user.click(confirm);
		await screen.findByRole("heading", { name: "Unsubscribe request processed" });
		expect(requests).toHaveLength(1);
		const [request] = requests;
		expect(request?.credentials).toBe("omit");
		expect(request?.headers.has("Referer")).toBe(false);
		expect(request?.headers.has("Authorization")).toBe(false);
		expect(request?.headers.has("X-XSRF-TOKEN")).toBe(false);
		expect(request?.headers.has("X-Impersonation-Allow-Writes")).toBe(false);
		expect((await request?.formData())?.get("List-Unsubscribe")).toBe("One-Click");
	});

	it("requires a fresh confirmation when another unsubscribe link is opened", async () => {
		const user = userEvent.setup();
		let writes = 0;
		server.use(
			http.post("*/notifications/unsubscribe/:token", () => {
				writes++;
				return new HttpResponse(null, { status: 204 });
			}),
		);
		const { router } = renderRouteAtWithRouter(`/unsubscribe?token=${token}`);
		await user.click(await screen.findByRole("button", { name: "Unsubscribe" }, ROUTE_RENDER_WAIT));
		await screen.findByRole("heading", { name: "Unsubscribe request processed" });
		await act(() =>
			router.navigate({ to: "/unsubscribe", search: { token: "another-capability" } }),
		);
		await screen.findByRole("button", { name: "Unsubscribe" });
		expect(writes).toBe(1);
	});

	it("allows an idempotent retry after an unconfirmed failure", async () => {
		const user = userEvent.setup();
		server.use(
			http.post(
				"*/notifications/unsubscribe/:token",
				() => HttpResponse.json({ status: 503 }, { status: 503 }),
				{ once: true },
			),
			http.post(
				"*/notifications/unsubscribe/:token",
				() => new HttpResponse(null, { status: 204 }),
			),
		);
		renderRouteAt(`/unsubscribe?token=${token}`);
		await user.click(await screen.findByRole("button", { name: "Unsubscribe" }, ROUTE_RENDER_WAIT));
		await screen.findByText("We couldn't confirm the result. You can safely try again.");
		await waitFor(() =>
			expect(screen.getByRole<HTMLButtonElement>("button", { name: "Unsubscribe" }).disabled).toBe(
				false,
			),
		);
		await user.click(screen.getByRole("button", { name: "Unsubscribe" }));
		await screen.findByRole("heading", { name: "Unsubscribe request processed" });
	});

	it("shows an incomplete-link explanation without offering a mutation", async () => {
		renderRouteAt("/unsubscribe");
		await screen.findByText(
			"This link is incomplete. Open the unsubscribe link from your email again.",
			undefined,
			ROUTE_RENDER_WAIT,
		);
		expect(screen.queryByRole("button", { name: "Unsubscribe" })).toBeNull();
	});
});
