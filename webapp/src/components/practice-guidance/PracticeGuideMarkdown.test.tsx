import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { PracticeGuideMarkdown } from "./PracticeGuideMarkdown";

const FIGURE = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><rect class="pv-fill-accent" width="10" height="10"/></svg>`;

const MARKDOWN = `## How to do it

Split by goal.

![Three changes in order](figures/split-order.svg)

![A tracking pixel](https://example.com/pixel.svg)

![A figure the guide does not carry](figures/missing.svg)
`;

describe("PracticeGuideMarkdown", () => {
	it("draws the guide's own figure, named by its alt text, and no other image", () => {
		render(
			<PracticeGuideMarkdown guide={{ markdown: MARKDOWN, figures: { "split-order": FIGURE } }} />,
		);

		const figure = screen.getByRole("figure");
		expect(screen.getByRole("img", { name: "Three changes in order" }).closest("figure")).toBe(
			figure,
		);
		expect(screen.getAllByRole("img")).toHaveLength(1);
		// A figure on a line of its own is a block, not a paragraph's child.
		expect(figure.closest("p")).toBeNull();
	});
});
