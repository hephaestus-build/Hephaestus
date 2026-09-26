import { fireEvent, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });

const NO_AI = /^No AI /u;

describe("the account AI choice on the settings page", () => {
	it("saves the answer and shows it as the saved one", async () => {
		const bodies: unknown[] = [];
		server.use(
			http.get("*/workspaces", () => HttpResponse.json([])),
			http.get("*/user/features", () => HttpResponse.json({})),
			http.put("*/user/ai-choice", async ({ request }) => {
				bodies.push(await request.json());
				return HttpResponse.json({ choice: "NO_AI", updatedAt: "2026-09-22T10:00:00Z" });
			}),
		);
		renderRouteAtWithRouter("/settings");
		await screen.findByRole("heading", { name: "Your AI choice" }, ROUTE_RENDER_WAIT);
		await screen.findByRole("radio", { name: NO_AI }, ROUTE_RENDER_WAIT);

		fireEvent.click(screen.getByRole("radio", { name: NO_AI }));
		expect(bodies).toStrictEqual([]);
		fireEvent.click(screen.getByRole("button", { name: "Save" }));

		await waitFor(() => expect(bodies).toStrictEqual([{ choice: "NO_AI" }]));
		await waitFor(() => {
			expect(screen.getByRole<HTMLInputElement>("radio", { name: NO_AI }).checked).toBe(true);
			// Saved: nothing is left to submit until the answer changes again.
			expect(screen.getByRole<HTMLButtonElement>("button", { name: "Save" }).disabled).toBe(true);
		});
	});
});
