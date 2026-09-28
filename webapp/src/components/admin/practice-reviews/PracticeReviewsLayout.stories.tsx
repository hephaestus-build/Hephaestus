import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";

import { PracticeReviewsLayout } from "./PracticeReviewsLayout";

/**
 * The page's header and its four sections, drawn as line tabs but made of links, since each changes
 * the URL. Which scope a section link carries is read from the URL, so it is proved by the route's
 * tests.
 */
const meta = {
	component: PracticeReviewsLayout,
	parameters: {
		layout: "fullscreen",
		chromatic: { viewports: [320, 1440] },
	},
	args: {
		workspaceSlug: "demo",
		children: <p className="text-sm text-muted-foreground">The section renders here.</p>,
	},
	argTypes: { children: { control: false } },
	decorators: [withStandardPage],
	tags: ["autodocs"],
} satisfies Meta<typeof PracticeReviewsLayout>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { level: 1 })).toHaveTextContent("Practice reviews");
		const sections = within(canvas.getByRole("navigation", { name: "Practice review sections" }));
		await expect(sections.getAllByRole("link").map((link) => link.textContent)).toEqual([
			"Overview",
			"Reviews",
			"Observations",
			"Feedback",
		]);
		await expect(sections.getByRole("link", { name: "Reviews" })).toHaveAttribute(
			"href",
			"/w/demo/admin/practices/reviews/runs",
		);
		canvas.getByText("The section renders here.");
	},
};

/** Four sections share the track at 320px rather than pushing the page sideways. */
export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		canvas.getByRole("navigation", { name: "Practice review sections" });
		await expectNoPageOverflow();
	},
};

/**
 * The router marks the section at the current address, and the tab's bar and weight follow that
 * mark; a section is a link, never a `tab`.
 */
export const CurrentSection: Story = {
	parameters: { router: { initialUrl: "/w/demo/admin/practices/reviews/feedback" } },
	play: async ({ canvas }) => {
		const sections = within(canvas.getByRole("navigation", { name: "Practice review sections" }));
		await expect(sections.getByRole("link", { name: "Feedback" })).toHaveAttribute(
			"aria-current",
			"page",
		);
		// Overview's address is a prefix of every other section's, so it is the one a fuzzy match would
		// also mark.
		for (const name of ["Overview", "Reviews", "Observations"]) {
			await expect(sections.getByRole("link", { name })).not.toHaveAttribute("aria-current");
		}
		await expect(sections.queryByRole("tab")).not.toBeInTheDocument();
	},
};
