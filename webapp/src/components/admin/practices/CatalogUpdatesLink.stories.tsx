import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { CatalogUpdatesLink } from "./CatalogUpdatesLink";

const meta = {
	component: CatalogUpdatesLink,
	tags: ["autodocs"],
	args: { workspaceSlug: "acme", count: 2 },
} satisfies Meta<typeof CatalogUpdatesLink>;

export default meta;
type Story = StoryObj<typeof meta>;

export const UpdatesWaiting: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Catalog updates (2)" })).toHaveAttribute(
			"href",
			"/w/acme/admin/practices/releases",
		);
	},
};

export const NoneWaiting: Story = {
	args: { count: 0 },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Catalog updates (0)" })).toBeVisible();
	},
};

// While the inbox loads, or when it could not load, the button names the page and guesses no count.
export const CountUnknown: Story = {
	args: { count: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Catalog updates" })).toBeVisible();
	},
};
