import { cleanup, screen, waitFor } from "@testing-library/react";
import { delay, HttpResponse, http } from "msw";
import { afterEach, beforeAll, describe, expect, it } from "vitest";

import { setPublicUsagePublished } from "@/hooks/use-public-usage-published";
import { unauthenticatedUser } from "@/mocks/handlers";
import { server } from "@/mocks/server";
import {
	ROUTE_RENDER_WAIT,
	loadRouteCode,
	renderRouteAt,
	testQueryClient,
} from "@/test/router-harness";

beforeAll(async () => loadRouteCode("/usage"), ROUTE_RENDER_WAIT.timeout);

// The footer flag lives as long as the document; each test starts from a page that the server wrote with it off.
afterEach(() => {
	setPublicUsagePublished(false);
});

const released = {
	periods: [
		{
			period: "2026-04",
			firstDay: "2026-04-01",
			lastDay: "2026-09-30",
			releasedOn: "2026-10-14",
			status: "PUBLISHED",
			figures: {
				workspaces: 40,
				reviewedPeople: 1200,
				reviewedWork: 18_400,
				practiceReviews: 96_400,
			},
		},
		{
			period: "2025-10",
			firstDay: "2025-10-01",
			lastDay: "2026-03-31",
			releasedOn: "2026-04-14",
			status: "NOT_ENOUGH_ACTIVITY",
			figures: {},
		},
	],
};

describe("Hephaestus in numbers", () => {
	it("shows the newest released half-year to a signed-out reader", async () => {
		server.use(
			unauthenticatedUser,
			http.get("*/public/usage", () => HttpResponse.json(released)),
		);
		renderRouteAt("/usage");

		await screen.findByRole("heading", { name: "April – September 2026" }, ROUTE_RENDER_WAIT);
		await screen.findByText("96,400");
		// The server sends no feedback figure for this half-year, so it reads as not published, never as zero.
		const feedback = screen.getByText("Practice feedback delivered").closest("div");
		expect(feedback?.textContent).toContain("Not published");
	});

	it("shows the half-year that the address names", async () => {
		server.use(
			unauthenticatedUser,
			http.get("*/public/usage", () => HttpResponse.json(released)),
		);
		renderRouteAt("/usage?period=2025-10");

		await screen.findByRole("heading", { name: "October 2025 – March 2026" }, ROUTE_RENDER_WAIT);
		await screen.findByText("Not enough activity to publish.");
	});

	it("says the server does not publish its figures while the page is off", async () => {
		server.use(unauthenticatedUser);
		renderRouteAt("/usage");

		await screen.findByText(
			"This server does not publish its figures.",
			undefined,
			ROUTE_RENDER_WAIT,
		);
	});

	it("shows no figure read before until this visit's read succeeds, and none after it fails", async () => {
		const queryClient = testQueryClient();
		server.use(
			unauthenticatedUser,
			http.get("*/public/usage", () => HttpResponse.json(released)),
		);
		renderRouteAt("/usage", queryClient);
		await screen.findByText("96,400", undefined, ROUTE_RENDER_WAIT);
		cleanup();

		// While this visit's read is open, the figures of the earlier visit stay hidden.
		server.use(
			http.get("*/public/usage", async () => {
				await delay(500);
				return HttpResponse.json({ status: 503, title: "Service Unavailable" }, { status: 503 });
			}),
		);
		renderRouteAt("/usage", queryClient);
		await screen.findByText("Loading the figures…", undefined, ROUTE_RENDER_WAIT);
		expect(screen.queryByText("96,400")).toBeNull();

		await screen.findByText("We could not load the figures", undefined, ROUTE_RENDER_WAIT);
		expect(screen.queryByText("96,400")).toBeNull();
		cleanup();

		server.use(
			http.get("*/public/usage", () =>
				HttpResponse.json({ status: 404, title: "Not Found" }, { status: 404 }),
			),
		);
		renderRouteAt("/usage", queryClient);
		await screen.findByText(
			"This server does not publish its figures.",
			undefined,
			ROUTE_RENDER_WAIT,
		);
		expect(screen.queryByText("96,400")).toBeNull();
	});

	it("shows no earlier 404 while this visit's read is open, and shows the figures once it succeeds", async () => {
		const queryClient = testQueryClient();
		server.use(
			unauthenticatedUser,
			http.get("*/public/usage", () => HttpResponse.json(released)),
		);
		renderRouteAt("/usage", queryClient);
		await screen.findByText("96,400", undefined, ROUTE_RENDER_WAIT);
		cleanup();

		server.use(
			http.get("*/public/usage", () =>
				HttpResponse.json({ status: 404, title: "Not Found" }, { status: 404 }),
			),
		);
		renderRouteAt("/usage", queryClient);
		await screen.findByText(
			"This server does not publish its figures.",
			undefined,
			ROUTE_RENDER_WAIT,
		);
		cleanup();

		// The page is on again. The cached 404 must not answer for this visit while its read is open.
		server.use(
			http.get("*/public/usage", async () => {
				await delay(500);
				return HttpResponse.json(released);
			}),
		);
		renderRouteAt("/usage", queryClient);
		await screen.findByText("Loading the figures…", undefined, ROUTE_RENDER_WAIT);
		expect(screen.queryByText("This server does not publish its figures.")).toBeNull();

		await screen.findByText("96,400", undefined, ROUTE_RENDER_WAIT);
		expect(screen.queryByText("This server does not publish its figures.")).toBeNull();
	});

	it("drops the footer link when this visit finds the page off, and shows it when the figures load", async () => {
		// The page loaded while the server published its figures.
		setPublicUsagePublished(true);
		server.use(
			unauthenticatedUser,
			http.get("*/public/usage", () =>
				HttpResponse.json({ status: 404, title: "Not Found" }, { status: 404 }),
			),
		);
		renderRouteAt("/usage");
		await screen.findByText(
			"This server does not publish its figures.",
			undefined,
			ROUTE_RENDER_WAIT,
		);
		await waitFor(() =>
			expect(screen.queryByRole("link", { name: "Hephaestus in numbers" })).toBeNull(),
		);
		cleanup();

		server.use(http.get("*/public/usage", () => HttpResponse.json(released)));
		renderRouteAt("/usage");
		await screen.findByRole("link", { name: "Hephaestus in numbers" }, ROUTE_RENDER_WAIT);
	});
});
