import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http, type PathParams } from "msw";
import { describe, expect, it, vi } from "vitest";

import type { PublicActivityChoice } from "@/api/types.gen";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });

describe("the account's public activity choice on the settings page", () => {
	it("hides the account on every public page at once, and shows it again", async () => {
		const user = userEvent.setup();
		const puts: unknown[] = [];
		let visible = true;
		server.use(
			http.get("*/workspaces", () => HttpResponse.json([])),
			http.get("*/user/features", () => HttpResponse.json({})),
			http.get("*/user/public-activity", () => HttpResponse.json({ visible })),
			http.put<PathParams, PublicActivityChoice>("*/user/public-activity", async ({ request }) => {
				const body = await request.json();
				puts.push(body);
				visible = body.visible;
				return HttpResponse.json(body);
			}),
		);
		renderRouteAtWithRouter("/settings");

		const control = await screen.findByRole(
			"switch",
			{ name: "Show me on public activity pages" },
			ROUTE_RENDER_WAIT,
		);
		expect(control.getAttribute("aria-checked")).toBe("true");
		await user.click(control);
		await waitFor(() => expect(control.getAttribute("aria-checked")).toBe("false"));
		await user.click(control);

		await waitFor(() => expect(puts).toStrictEqual([{ visible: false }, { visible: true }]));
		await waitFor(() => expect(control.getAttribute("aria-checked")).toBe("true"));
	});
});
