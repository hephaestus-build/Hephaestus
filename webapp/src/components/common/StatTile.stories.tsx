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

/** The figure in the foreground, what it counts after it, and the page's content under both. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Pieces of work reviewed")).toBeVisible();
		const figure = canvas.getByText("17");
		await expect(figure).toHaveClass("text-foreground");
		await expect(canvas.getByText("in the last 30 days")).toBeVisible();
		await expect(canvas.getByText("Typical range: 11 to 21")).toBeVisible();
	},
};

/** A tile with nothing behind it: the figure drops to the muted tone, and the card with it. */
export const Muted: Story = {
	args: { variant: "muted", muted: true, value: 0, children: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("0")).toHaveClass("text-muted-foreground");
		await expect(canvas.queryByText("Typical range: 11 to 21")).toBeNull();
	},
};

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

/** The tile's shape while its figure loads, with the page's content in its own shape under it. */
export const Loading: Story = {
	render: () => (
		<StatTileSkeleton>
			<Skeleton className="h-16 w-full" />
		</StatTileSkeleton>
	),
	play: async ({ canvasElement }) => {
		// The shape alone, hidden from assistive technology: the page says once that it is loading.
		await expect(canvasElement.querySelector('[data-slot="card"]')).toHaveAttribute(
			"aria-hidden",
			"true",
		);
	},
};
