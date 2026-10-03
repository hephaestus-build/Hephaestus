import { act, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { renderWithRouter } from "@/test/router-harness";

import { RouteAnnouncer } from "./RouteAnnouncer";

async function mount() {
	document.title = "Landing · Hephaestus";
	return renderWithRouter(<RouteAnnouncer />, "/");
}

describe("RouteAnnouncer", () => {
	it("stays silent for the first page", async () => {
		await mount();

		expect(screen.queryByText("Landing · Hephaestus")).toBeNull();
	});

	it("speaks the title of the page it navigated to", async () => {
		const { router } = await mount();

		document.title = "Settings · Hephaestus";
		await act(async () => router.navigate({ to: "/settings" }));

		screen.getByText("Settings · Hephaestus");
	});

	it("stays silent when only the search changes", async () => {
		const { router } = await mount();

		document.title = "Landing, filtered · Hephaestus";
		await act(async () => router.navigate({ to: "/", search: { survey: "pilot" } }));

		expect(screen.queryByText("Landing, filtered · Hephaestus")).toBeNull();
	});
});
