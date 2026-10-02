import type { Meta, StoryObj } from "@storybook/react-vite";

import { ChatSkeleton } from "./ChatSkeleton";

const meta = {
	component: ChatSkeleton,
	parameters: { layout: "fullscreen" },
	decorators: [
		(Story) => (
			<div className="h-dvh">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof ChatSkeleton>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
