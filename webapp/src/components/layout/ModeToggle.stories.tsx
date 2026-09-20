import type { Meta, StoryObj } from "@storybook/react";

import { ModeToggle } from "./ModeToggle";

/**
 * The ModeToggle component allows users to switch between light, dark, and system
 * theme preferences. It provides a dropdown menu with theme options and displays
 * the appropriate sun/moon icon based on the current theme.
 */
const meta = {
	component: ModeToggle,
	parameters: {
		layout: "centered",
		docs: {
			description: {
				component:
					"A theme switcher button with dropdown options for light, dark, and system preferences.",
			},
		},
	},
	tags: ["autodocs"],
} satisfies Meta<typeof ModeToggle>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Default mode toggle component with dropdown for theme selection.
 * The component requires ThemeProvider context to function properly.
 */
export const Default: Story = {};
