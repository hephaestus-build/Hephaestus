import type { Meta, StoryObj } from "@storybook/react";

import { AboutMissionSection } from "./AboutMissionSection";

const meta = {
	component: AboutMissionSection,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
} satisfies Meta<typeof AboutMissionSection>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
