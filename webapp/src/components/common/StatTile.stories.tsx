import type { Meta, StoryObj } from "@storybook/react-vite";
import { GitPullRequestIcon } from "lucide-react";
import { expect } from "storybook/test";

import { precedes } from "@/test/dom";

import { Skeleton } from "@/components/ui/skeleton";

import { StatTile, StatTileSkeleton } from "./StatTile";

/**
 * The stat tile Activity and Practices across the workspace share: the title with its glyph, the
 * headline figure and what it counts, then whatever the page shows under it. Each page's own
 * stories show it filled; these pin the tile's own branches.
 */
const meta = {
	component: StatTile,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	decorators: [
		(Story) => (
			<div className="w-72">
				<Story />
			</div>
		),
	],
	args: {
		icon: <GitPullRequestIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />,
		title: "Pieces of work reviewed",
		value: 17,
		qualifier: "in the last 30 days",
		children: <p className="text-sm text-muted-foreground">Typical range: 11 to 21</p>,
	},
} satisfies Meta<typeof StatTile>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

/** A tile with nothing behind it: `variant="muted"` mutes the figure with the card. */
export const Muted: Story = { args: { variant: "muted", value: 0, children: undefined } };

/** A comparison with the period before, under the figure and above the page's content. */
export const WithDetail: Story = {
	args: {
		detail: <p className="text-xs text-muted-foreground">4 more than the 30 days before</p>,
	},
	play: async ({ canvas }) => {
		const detail = canvas.getByText("4 more than the 30 days before");
		await expect(detail).toBeVisible();
		// Read in order: the figure, its comparison, then what the page shows under it.
		await expect(precedes(canvas.getByText("17"), detail)).toBe(true);
		await expect(precedes(detail, canvas.getByText("Typical range: 11 to 21"))).toBe(true);
	},
};

/** `StatTileSkeleton`, with `children` as the page's content in its loading shape. */
export const Loading: Story = {
	args: { children: <Skeleton className="h-16 w-full" /> },
	render: (args) => <StatTileSkeleton>{args.children}</StatTileSkeleton>,
	play: async ({ canvasElement }) => {
		// The shape alone, hidden from assistive technology: the page says once that it is loading.
		await expect(canvasElement.querySelector('[data-slot="card"]')).toHaveAttribute(
			"aria-hidden",
			"true",
		);
	},
};
