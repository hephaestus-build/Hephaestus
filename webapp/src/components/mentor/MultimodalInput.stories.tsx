import type { Meta, StoryObj } from "@storybook/react";
import { type ComponentProps, useState } from "react";
import { expect, fn, userEvent, waitFor } from "storybook/test";

import { MultimodalInput } from "./MultimodalInput";

/**
 * MultimodalInput component provides a rich text input interface with file upload capabilities.
 * Features auto-resizing textarea, attachment previews, suggested actions, and submission controls.
 * Now with smart internal state management - handles its own input state while exposing a clean API for business logic.
 */
const meta = {
	component: MultimodalInput,
	tags: ["autodocs"],
	argTypes: {
		status: {
			description: "Current upload/submission status",
			control: "select",
			options: ["ready", "submitted", "error"],
		},
		onStop: {
			description: "Handler for stopping current submission",
			control: false,
		},
		attachments: {
			description: "Array of current attachments",
			control: "object",
		},
		onAttachmentsChange: {
			description: "Handler for attachment changes",
			control: false,
		},
		onFileUpload: {
			description: "Handler for file upload processing",
			control: false,
		},
		onSubmit: {
			description: "Handler for form submission with text and attachments",
			control: false,
		},
		placeholder: {
			description: "Placeholder text for textarea",
			control: "text",
		},
		initialInput: {
			description: "Initial input value",
			control: "text",
		},
		readonly: {
			description: "Whether the input should be readonly",
			control: "boolean",
		},
		disableAttachments: {
			description: "Whether to disable attachment functionality",
			control: "boolean",
		},
	},
	args: {
		status: "ready",
		onStop: fn(),
		attachments: [],
		onAttachmentsChange: fn(),
		onFileUpload: fn(async () => []),
		onSubmit: fn(),
		// Suggested actions send immediately via onSubmit; no handler required
		placeholder: "Send a message...",
		initialInput: "",
		readonly: false,
		disableAttachments: false,
	},
	decorators: [
		(Story) => (
			<div className="max-w-2xl w-full pt-20">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof MultimodalInput>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Default empty state.
 */
export const Default: Story = {};

/**
 * Input with some initial text.
 */
export const WithInitialText: Story = {
	args: {
		initialInput: "What are the best practices for React component design?",
	},
};

/**
 * Input with attached files showing preview thumbnails.
 */
export const WithAttachments: Story = {
	args: {
		attachments: [
			{
				name: "screenshot.png",
				url: "https://picsum.photos/seed/screenshot/200/150",
				contentType: "image/png",
			},
			{
				name: "document.pdf",
				url: "https://example.com/document.pdf",
				contentType: "application/pdf",
			},
		],
	},
};

/**
 * Submission in progress showing stop button.
 */
export const Submitting: Story = {
	args: {
		status: "submitted",
	},
};

/**
 * Custom placeholder text demonstration.
 */
export const CustomPlaceholder: Story = {
	args: {
		placeholder: "Ask me anything about your project...",
	},
};

/**
 * Readonly input state.
 */
export const ReadonlyInput: Story = {
	args: {
		readonly: true,
		initialInput: "This input is readonly",
	},
};

/**
 * Input with attachments disabled - no attachment button or file upload.
 */
export const DisabledAttachments: Story = {
	args: {
		disableAttachments: true,
		placeholder: "Send a message (attachments disabled)...",
	},
};

function ScrollPositionHarness(args: ComponentProps<typeof MultimodalInput>) {
	const [isAtBottom, setIsAtBottom] = useState(true);
	return (
		<>
			<button type="button" onClick={() => setIsAtBottom(false)}>
				Read earlier messages
			</button>
			<MultimodalInput
				{...args}
				isAtBottom={isAtBottom}
				scrollToBottom={() => setIsAtBottom(true)}
			/>
		</>
	);
}

export const ScrollToLatest: Story = {
	render: (args) => <ScrollPositionHarness {...args} />,
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: "Scroll to latest message" })).toBeNull();
		await userEvent.click(canvas.getByRole("button", { name: "Read earlier messages" }));
		const latest = await canvas.findByRole("button", { name: "Scroll to latest message" });
		await waitFor(() => expect(latest).toBeVisible());
		await userEvent.click(latest);
		await waitFor(() =>
			expect(canvas.queryByRole("button", { name: "Scroll to latest message" })).toBeNull(),
		);
	},
};
