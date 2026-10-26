import type { Meta, StoryObj } from "@storybook/react";

import { LandingProductFeaturesSection } from "./LandingProductFeaturesSection";

const meta = {
	component: LandingProductFeaturesSection,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
} satisfies Meta<typeof LandingProductFeaturesSection>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Mobile: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
};

export const DarkMode: Story = {
	globals: { theme: "dark" },
};
