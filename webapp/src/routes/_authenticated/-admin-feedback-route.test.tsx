import { act, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";

import { adminListWorkspacesQueryKey } from "@/api/@tanstack/react-query.gen";
import { server } from "@/mocks/server";
import { renderRouteAt, ROUTE_RENDER_WAIT } from "@/test/router-harness";

describe("product feedback administration", () => {
	it("retains an authored draft across inbox tabs and a failed audience refresh", async () => {
		server.use(
			http.get("*/admin/product-feedback", () => HttpResponse.json({ content: [] })),
			http.get("*/admin/product-feedback/responses", () => HttpResponse.json({ content: [] })),
			http.get("*/admin/product-feedback/surveys", () => HttpResponse.json({ content: [] })),
			http.get("*/workspaces/:workspaceSlug/product-feedback/surveys", () => HttpResponse.json([])),
			http.get("*/admin/workspaces", () => HttpResponse.json([])),
		);
		const user = userEvent.setup();
		const client = renderRouteAt("/admin/feedback");
		await user.click(await screen.findByRole("tab", { name: "Surveys" }, ROUTE_RENDER_WAIT));
		await user.type(
			await screen.findByRole("textbox", { name: "Title" }),
			"Keep this survey draft",
		);
		await user.click(screen.getByRole("tab", { name: "Feedback" }));
		await user.click(screen.getByRole("tab", { name: "Surveys" }));
		expect(screen.getByRole("textbox", { name: "Title" })).toHaveProperty(
			"value",
			"Keep this survey draft",
		);
		server.use(
			http.get("*/admin/workspaces", () => HttpResponse.json({ status: 503 }, { status: 503 })),
		);
		await act(async () => {
			await client.invalidateQueries({ queryKey: adminListWorkspacesQueryKey() });
		});
		await screen.findByText("Workspace audiences couldn't be loaded");
		expect(screen.getByRole("textbox", { name: "Title" })).toHaveProperty(
			"value",
			"Keep this survey draft",
		);
	});
});
