import type { Meta, StoryObj } from "@storybook/react";
import { LandingProjectOriginsSection } from "./LandingProjectOriginsSection";

const meta = {
	component: LandingProjectOriginsSection,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
} satisfies Meta<typeof LandingProjectOriginsSection>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Mobile: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
};
