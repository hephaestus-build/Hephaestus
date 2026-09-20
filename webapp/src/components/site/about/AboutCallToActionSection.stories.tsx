import type { Meta, StoryObj } from "@storybook/react";

import { AboutCallToActionSection } from "./AboutCallToActionSection";

const meta = {
	component: AboutCallToActionSection,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
} satisfies Meta<typeof AboutCallToActionSection>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
