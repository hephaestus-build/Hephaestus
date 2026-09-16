import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent } from "storybook/test";

import type { InstanceLlmSettings } from "@/api/types.gen";

import { InstanceLlmSettingsCard } from "./InstanceLlmSettingsCard";

const mockSettings: InstanceLlmSettings = {
	allowWorkspaceConnections: true,
	allowedEgressHosts: "api.openai.com\nllm.example.com",
};

const meta = {
	component: InstanceLlmSettingsCard,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		settings: mockSettings,
		isLoading: false,
		isSubmitting: false,
		onSave: fn(),
	},
	decorators: [
		(Story) => (
			<div className="max-w-xl">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof InstanceLlmSettingsCard>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const NoAllowlistOwnProviderDisabled: Story = {
	args: {
		settings: { allowWorkspaceConnections: false },
	},
};

export const Loading: Story = {
	args: { settings: undefined, isLoading: true },
};

export const EditedButUnsaved: Story = {
	play: async ({ canvas }) => {
		const ownProvider = canvas.getByRole("switch", {
			name: /let workspaces add providers and models/iu,
		});
		await userEvent.click(ownProvider);
		await expect(ownProvider).toHaveAttribute("aria-checked", "false");
	},
};
