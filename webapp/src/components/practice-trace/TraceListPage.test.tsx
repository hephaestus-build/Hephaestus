import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { renderWithRouter } from "@/test/router-harness";

import { tracedArtifacts } from "./fixtures";
import { TraceListPage } from "./TraceListPage";

/**
 * Not a story: the page links are built with `search={(previous) => …}`, where `previous` is the
 * *router's* search params rather than the component's prop, so proving they carry the filter takes
 * a router whose location has one — which the Storybook preview's shared router does not.
 */
describe("paging a filtered list", () => {
	function renderAtPage(page: number) {
		return renderWithRouter(
			<TraceListPage
				workspaceSlug="demo"
				search={{ kind: "scm.issue", page: page || undefined }}
				onSearchChange={vi.fn()}
				artifacts={{
					content: tracedArtifacts.slice(0, 2),
					page: { number: page, size: 2, totalElements: 6, totalPages: 3 },
				}}
				isLoading={false}
				error={undefined}
				onRetry={vi.fn()}
			/>,
			`/w/demo/reviews?kind=scm.issue${page ? `&page=${page}` : ""}`,
		);
	}

	it("keeps the filter while changing the page", async () => {
		await renderAtPage(0);

		const next = await screen.findByRole("link", { name: "Go to page 2" });
		expect(next.getAttribute("href")).toBe("/w/demo/reviews?kind=scm.issue&page=1");
	});

	it("leaves the first page out of the URL rather than writing page=0", async () => {
		await renderAtPage(1);

		const previous = await screen.findByRole("link", { name: "Go to page 1" });
		expect(previous.getAttribute("href")).toBe("/w/demo/reviews?kind=scm.issue");
	});
});
