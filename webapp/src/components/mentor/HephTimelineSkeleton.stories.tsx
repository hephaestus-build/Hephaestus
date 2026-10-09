import type { Meta, StoryObj } from "@storybook/react-vite";

import { HephTimelineSkeleton } from "./HephTimelineSkeleton";

const meta = {
	component: HephTimelineSkeleton,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
} satisfies Meta<typeof HephTimelineSkeleton>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
