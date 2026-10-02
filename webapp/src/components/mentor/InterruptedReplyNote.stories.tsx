import type { Meta, StoryObj } from "@storybook/react";

import { InterruptedReplyNote } from "./InterruptedReplyNote";

/** Shown under a reply the server stored as interrupted, so its incompleteness is never mistaken for an answer. */
const meta = {
	component: InterruptedReplyNote,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
} satisfies Meta<typeof InterruptedReplyNote>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The one sentence it carries. */
export const Default: Story = {};
