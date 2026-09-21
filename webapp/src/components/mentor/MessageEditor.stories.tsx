import type { Meta, StoryObj } from "@storybook/react";
import { fn } from "storybook/test";

import { MessageEditor } from "./MessageEditor";

/**
 * MessageEditor component provides an inline editing interface for chat messages.
 * Pure component that handles text editing with auto-resize and keyboard shortcuts.
 */
const meta = {
	component: MessageEditor,
	tags: ["autodocs"],
	argTypes: {
		initialContent: {
			description: "Initial text content to edit",
			control: "text",
		},
		isSubmitting: {
			description: "Whether the editor is currently submitting",
			control: "boolean",
		},
		placeholder: {
			description: "Placeholder text for the textarea",
			control: "text",
		},
		onCancel: {
			description: "Callback when cancel is clicked",
			control: false,
		},
		onSend: {
			description: "Callback when send is clicked with the edited content",
			control: false,
		},
		className: {
			description: "Optional CSS class name",
			control: "text",
		},
	},
	args: {
		initialContent: "This is a sample message that can be edited.",
		isSubmitting: false,
		placeholder: "Edit your message...",
		onCancel: fn(),
		onSend: fn(),
	},
	decorators: [
		(Story) => (
			<div className="w-full max-w-2xl">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof MessageEditor>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Default editor state with sample content.
 */
export const Default: Story = {};

/**
 * Editor with longer content that demonstrates auto-resize functionality.
 */
export const LongContent: Story = {
	args: {
		initialContent: `This is a much longer message that demonstrates how the MessageEditor component handles multi-line content.

It includes multiple paragraphs and line breaks, showing how the textarea automatically resizes to fit the content.

Here's some sample code:
\`\`\`javascript
function example() {
  console.log("Hello, world!");
  return "This shows how code blocks are handled";
}
\`\`\`

The editor maintains all formatting and provides a smooth editing experience for both short and long content.`,
	},
};

/**
 * Editor in submitting state with disabled controls.
 */
export const Submitting: Story = {
	args: {
		isSubmitting: true,
	},
};

/**
 * Empty editor showing placeholder text.
 */
export const Empty: Story = {
	args: {
		initialContent: "",
		placeholder: "Start typing your message...",
	},
};

/**
 * Editor with custom placeholder text.
 */
export const CustomPlaceholder: Story = {
	args: {
		initialContent: "",
		placeholder: "Write your thoughts here...",
	},
};

/**
 * Code editing example with technical content.
 */
export const CodeContent: Story = {
	args: {
		initialContent: `function calculateSum(a, b) {
  return a + b;
}

// This function adds two numbers together
const result = calculateSum(5, 3);
console.log(result); // Output: 8`,
		placeholder: "Edit your code...",
	},
};

/**
 * Markdown content editing example.
 */
export const MarkdownContent: Story = {
	args: {
		initialContent: `# Heading

This is a **bold** statement with *italic* text.

## Subheading

- List item 1
- List item 2
- List item 3

> This is a blockquote

[Link to example](https://example.com)`,
		placeholder: "Edit markdown content...",
	},
};
