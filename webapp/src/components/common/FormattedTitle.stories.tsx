import type { Meta, StoryObj } from "@storybook/react";

import { FormattedTitle } from "./FormattedTitle";

/**
 * FormattedTitle component renders text with inline code segments highlighted.
 * It automatically parses text wrapped in backticks (`) and renders them as code elements.
 */
const meta = {
	component: FormattedTitle,
	tags: ["autodocs"],
	parameters: {
		layout: "padded",
	},
	argTypes: {
		title: {
			description: "Text content with optional code segments wrapped in backticks (`)",
			control: "text",
		},
		className: {
			description: "Additional CSS classes to apply to the container div",
			control: "text",
		},
	},
} satisfies Meta<typeof FormattedTitle>;

export default meta;
type Story = StoryObj<typeof FormattedTitle>;

/**
 * Basic title with no code segments.
 */
export const Basic: Story = {
	args: {
		title: "This is a simple title with no code",
	},
};

/**
 * Title containing inline code segments that are highlighted.
 */
export const WithCode: Story = {
	args: {
		title: "Use `npm install` to install dependencies and `npm start` to run the application.",
	},
};

/**
 * Title with multiple consecutive code segments.
 */
export const MultipleCodeSegments: Story = {
	args: {
		title: "Compare `value1` with `value2` and then call `processResult()`",
	},
};

/**
 * Title with custom styling applied.
 */
export const WithCustomStyling: Story = {
	args: {
		title: 'Run `git commit -m "your message"` to commit changes',
		className: "font-bold text-lg",
	},
};
