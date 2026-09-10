import type { Meta, StoryObj } from "@storybook/react-vite";

import { WorkspaceMentorPreferenceNotice } from "./WorkspaceMentorPreferenceNotice";

const meta = {
	component: WorkspaceMentorPreferenceNotice,
	tags: ["autodocs"],
	args: { workspaceSlug: "engineering", reason: "no-ai" },
} satisfies Meta<typeof WorkspaceMentorPreferenceNotice>;
export default meta;
type Story = StoryObj<typeof meta>;
export const NoAi: Story = {};
export const ChoiceRequired: Story = { args: { reason: "choice-required" } };
export const Unavailable: Story = { args: { reason: "unavailable" } };
