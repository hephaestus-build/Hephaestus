import type { Meta, StoryObj } from "@storybook/react";

import { MentorAvatar } from "./MentorAvatar";

/**
 * MentorAvatar component displays the AI mentor's avatar using our custom animated HephIcon.
 * Designed to provide visual consistency and friendly interaction across the mentor interface.
 */
const meta = {
	component: MentorAvatar,
	parameters: {
		layout: "centered",
	},
	tags: ["autodocs"],
	argTypes: {
		size: {
			control: { type: "select" },
			options: ["default", "sm", "lg"],
			description: "Size variant of the avatar",
		},
		className: {
			control: "text",
			description: "Additional CSS classes for styling",
		},
	},
} satisfies Meta<typeof MentorAvatar>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Default mentor avatar with animated icon used in most message contexts.
 */
export const Default: Story = {};

/**
 * Small size variant for compact interfaces.
 */
export const Small: Story = {
	args: {
		size: "sm",
	},
};

/**
 * Large size variant for prominent display.
 */
export const Large: Story = {
	args: {
		size: "lg",
	},
};

/**
 * Avatar with custom styling applied.
 */
export const CustomStyle: Story = {
	args: {
		className: "border-2 border-brand shadow-lg",
	},
};

/**
 * Example of how the MentorAvatar appears in a typical chat interface.
 */
export const ChatExample: Story = {
	render: () => (
		<div className="flex max-w-md items-start gap-3 p-4">
			<MentorAvatar />
			<div className="flex-1 space-y-1">
				<div className="rounded-lg bg-muted p-3">
					<p className="text-sm">
						Hello! I'm your friendly mentor bot. How can I help you learn something new today?
					</p>
				</div>
				<p className="text-xs text-muted-foreground">Just now</p>
			</div>
		</div>
	),
};
