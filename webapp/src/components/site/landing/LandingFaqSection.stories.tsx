import type { Meta, StoryObj } from "@storybook/react";
import { LandingFaqSection } from "./LandingFaqSection";

const meta = {
	component: LandingFaqSection,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
} satisfies Meta<typeof LandingFaqSection>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
