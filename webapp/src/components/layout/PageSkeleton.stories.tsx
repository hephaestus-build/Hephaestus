import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { StandardPageSurface } from "@/components/layout/StandardPageSurface";
import { expectNoPageOverflow } from "@/stories/reflow";

import { PageSkeleton } from "./PageSkeleton";

/**
 * Shown by the router while a guarded page checks the session, after its one-second pending delay.
 * The shape of a page header and one section, not a spinner in a fixed box, so the page that follows
 * does not jump.
 */
const meta = {
	component: PageSkeleton,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
	decorators: [
		(Story) => (
			<StandardPageSurface>
				<Story />
			</StandardPageSurface>
		),
	],
} satisfies Meta<typeof PageSkeleton>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		const status = canvas.getByText("Loading page");
		await expect(status).toHaveClass("sr-only");
		await expect(status.parentElement).toHaveAttribute("aria-busy", "true");
		// Nothing stands in for the page's real heading.
		await expect(canvas.queryByRole("heading")).not.toBeInTheDocument();
	},
};

export const Dark: Story = { globals: { theme: "dark" } };

export const OnAPhone: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: expectNoPageOverflow,
};
