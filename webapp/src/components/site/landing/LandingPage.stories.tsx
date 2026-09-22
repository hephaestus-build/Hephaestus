import type { Meta, StoryObj } from "@storybook/react";
import { fn } from "storybook/test";

import { LandingPage } from "./LandingPage";

const meta = {
	component: LandingPage,
	tags: ["autodocs"],
	parameters: {
		layout: "fullscreen",
	},
	args: {
		onSignIn: fn(),
	},
} satisfies Meta<typeof LandingPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Mobile: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
};

export const DarkMode: Story = {
	globals: { theme: "dark" },
};
