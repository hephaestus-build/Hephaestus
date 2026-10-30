import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ObserverStub } from "@/test/observers";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

import { OVERVIEW, serveAttention } from "./-overview-attention-server";

vi.setConfig({ testTimeout: 30_000 });

// jsdom needs an IntersectionObserver stub for paged lists.
beforeEach(() => {
	vi.stubGlobal("IntersectionObserver", ObserverStub);
});

/**
 * The overview's figures are real links: a click puts the filter into the list's URL, so the
 * browser's back returns to the overview and the address can be shared.
 */
describe("the overview's figures open the lists filtered to them", () => {
	it("opens the Feedback table filtered to a Feedback tile count over the range, and back", async () => {
		serveAttention();
		const { router } = renderRouteAtWithRouter(`${OVERVIEW}?range=30d`);

		const legend = await screen.findByRole(
			"list",
			{ name: "Feedback by delivery" },
			ROUTE_RENDER_WAIT,
		);
		await userEvent.click(within(legend).getByRole("link", { name: "60 delivered" }));
		await waitFor(() =>
			expect(router.state.location.pathname).toBe("/w/acme/admin/practices/reviews/feedback"),
		);
		expect(router.state.location.search).toMatchObject({ state: "DELIVERED" });
		expect(router.state.location.search).toHaveProperty("from");

		router.history.back();
		await waitFor(() => expect(router.state.location.pathname).toBe(OVERVIEW));
		await screen.findByRole("list", { name: "Feedback by delivery" }, ROUTE_RENDER_WAIT);
	});

	it("opens the Reviews table filtered to what the queue counts from See all", async () => {
		serveAttention();
		const { router } = renderRouteAtWithRouter(`${OVERVIEW}?range=30d`);

		await userEvent.click(await screen.findByRole("link", { name: "See all" }, ROUTE_RENDER_WAIT));
		await waitFor(() =>
			expect(router.state.location.pathname).toBe("/w/acme/admin/practices/reviews"),
		);
		expect(router.state.location.search).toMatchObject({ status: ["RUNNING", "QUEUED"] });
	});

	it("opens all time with no first day, and its figures' lists with no date filter", async () => {
		const { overviewReads } = serveAttention();
		const { router } = renderRouteAtWithRouter(`${OVERVIEW}?range=all`);

		const allTime = await screen.findByRole("button", { name: "All time" }, ROUTE_RENDER_WAIT);
		await waitFor(() => expect(allTime.getAttribute("aria-pressed")).toBe("true"));
		// All time asks for no first day, and reads no period before it.
		await waitFor(() => expect(overviewReads.length).toBeGreaterThan(0));
		expect(overviewReads.every((from) => from === "")).toBe(true);

		// So a figure's list has no date filter either, and holds exactly the rows it counts.
		const legend = await screen.findByRole(
			"list",
			{ name: "Feedback by delivery" },
			ROUTE_RENDER_WAIT,
		);
		await userEvent.click(within(legend).getByRole("link", { name: /^\d+ delivered$/u }));
		await waitFor(() =>
			expect(router.state.location.pathname).toBe("/w/acme/admin/practices/reviews/feedback"),
		);
		expect(router.state.location.search).toMatchObject({ state: "DELIVERED" });
		expect(router.state.location.search).not.toHaveProperty("from");
		expect(router.state.location.search).not.toHaveProperty("to");
	});
});

/**
 * The lists have their own dates, so a tab link does not carry the overview's range. The range is
 * remembered while the browser tab is open instead, and the overview restores it on return.
 */
describe("the overview's range across the tabs", () => {
	it("restores the chosen range on return from a list, unless the address names one", async () => {
		serveAttention();
		const { router } = renderRouteAtWithRouter(OVERVIEW);

		await userEvent.click(
			await screen.findByRole("button", { name: "All time" }, ROUTE_RENDER_WAIT),
		);
		const tabs = screen.getByRole("navigation", { name: "Practice review sections" });
		await userEvent.click(within(tabs).getByRole("link", { name: /^Reviews/u }));
		await waitFor(() =>
			expect(router.state.location.pathname).toBe("/w/acme/admin/practices/reviews"),
		);
		expect(router.state.location.search).not.toHaveProperty("range");

		await userEvent.click(within(tabs).getByRole("link", { name: /^Overview/u }));
		await waitFor(() => expect(router.state.location.pathname).toBe(OVERVIEW));
		await waitFor(
			() => expect(router.state.location.search).toMatchObject({ range: "all" }),
			ROUTE_RENDER_WAIT,
		);
		const allTime = await screen.findByRole("button", { name: "All time" }, ROUTE_RENDER_WAIT);
		expect(allTime.getAttribute("aria-pressed")).toBe("true");

		// An address that names a range wins over the remembered one, and is remembered in turn.
		router.history.push(`${OVERVIEW}?range=7d`);
		await waitFor(
			() =>
				expect(screen.getByRole("button", { name: "7 days" }).getAttribute("aria-pressed")).toBe(
					"true",
				),
			ROUTE_RENDER_WAIT,
		);
		router.history.push("/w/acme/admin/practices/reviews");
		await waitFor(() =>
			expect(router.state.location.pathname).toBe("/w/acme/admin/practices/reviews"),
		);
		router.history.push(OVERVIEW);
		await waitFor(
			() => expect(router.state.location.search).toMatchObject({ range: "7d" }),
			ROUTE_RENDER_WAIT,
		);
	});
});
