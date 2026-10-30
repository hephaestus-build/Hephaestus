import type { Meta, StoryObj } from "@storybook/react";

import { Greeting } from "./Greeting";

/**
 * Greeting component displays a welcoming message with smooth animations.
 * Perfect for empty states, chat introductions, or onboarding flows.
 */
const meta = {
	component: Greeting,
	parameters: {
		layout: "centered",
	},
	tags: ["autodocs"],
} satisfies Meta<typeof Greeting>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Default greeting with animated entrance.
 */
export const Default: Story = {};
