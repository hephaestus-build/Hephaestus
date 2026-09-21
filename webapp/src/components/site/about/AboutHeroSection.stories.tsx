import type { Meta, StoryObj } from "@storybook/react";

import { AboutHeroSection } from "./AboutHeroSection";

const meta = {
	component: AboutHeroSection,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
} satisfies Meta<typeof AboutHeroSection>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
