import type { Meta, StoryObj } from "@storybook/react";
import { fn } from "storybook/test";
import { LandingCtaSection } from "./LandingCtaSection";

const meta = {
	component: LandingCtaSection,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		onSignIn: fn(),
	},
} satisfies Meta<typeof LandingCtaSection>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const DarkMode: Story = {
	globals: { theme: "dark" },
};
