import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";
import { InstitutionalAttribution } from "./InstitutionalAttribution";

const meta = {
	component: InstitutionalAttribution,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
} satisfies Meta<typeof InstitutionalAttribution>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("link", { name: /Applied Education Technologies/u }),
		).toHaveAttribute("href", "https://aet.cit.tum.de/");
		await expect(
			canvas.getByRole("link", { name: /Technical University of Munich/u }),
		).toHaveAttribute("href", "https://www.tum.de/en/");
	},
};

export const Mobile: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
};

export const DarkMode: Story = {
	globals: { theme: "dark" },
};
