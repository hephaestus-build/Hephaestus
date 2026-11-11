import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeAll, describe, expect, it } from "vitest";

import { loadRouteCode, ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

import { OVERVIEW, serveAttention } from "./-overview-attention-server";

beforeAll(
	() => loadRouteCode("/w/acme/admin/practices/reviews/overview"),
	ROUTE_RENDER_WAIT.timeout,
);

describe("the overview's review time", () => {
	it("shows nothing the Reviews list cannot open, not even in the review time card's (i)", async () => {
		serveAttention();
		renderRouteAtWithRouter(OVERVIEW);

		const about = await screen.findByRole(
			"button",
			{ name: "About review time" },
			ROUTE_RENDER_WAIT,
		);
		// No list opens exactly the feedback the merge withheld, so it is no figure of the card.
		expect(screen.queryByText(/lost to merge$/u)).toBeNull();
		await userEvent.click(about);
		const info = await screen.findByRole("dialog", { name: "About review time" });
		expect(info.textContent).not.toContain("merge");
		await userEvent.keyboard("{Escape}");
		// The slowest tenth is gone; the median is split into the trace's five stages instead, with
		// no unsplit run, since every review recorded when its execution started.
		expect(screen.queryByText("Slowest 1 in 10")).toBeNull();
		expect(
			screen.getByRole("list", { name: "Where the time went" }).querySelectorAll("li"),
		).toHaveLength(5);
	});
});
