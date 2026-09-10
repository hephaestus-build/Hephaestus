import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import type { WorkspaceOnboardingSettings } from "@/api/types.gen";
import { expectGenuinelyDisabled } from "@/test/controls";
import { expectNoPageOverflow } from "@/test/reflow";
import { WorkspaceOnboardingSettingsPage } from "./WorkspaceOnboardingSettingsPage";

const settings = {
	enabled: false,
	revision: 0,
	welcomeMarkdown: "",
	requiredConnectionIds: [],
} satisfies WorkspaceOnboardingSettings;
const meta = {
	title: "Workspace admin/Member onboarding",
	component: WorkspaceOnboardingSettingsPage,
	tags: ["autodocs"],
	parameters: { layout: "fullscreen", chromatic: { viewports: [320, 1440] } },
	args: {
		workspaceSlug: "engineering",
		state: { status: "ready", settings, links: [] },
		onSave: fn(),
	},
} satisfies Meta<typeof WorkspaceOnboardingSettingsPage>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Empty: Story = {};
export const Loading: Story = { args: { state: { status: "loading" } } };
export const Error: Story = {
	args: { state: { status: "error", error: new globalThis.Error("Unavailable"), onRetry: fn() } },
};
export const Configured: Story = {
	args: {
		state: {
			status: "ready",
			settings: {
				...settings,
				enabled: true,
				revision: 1,
				welcomeMarkdown: "Welcome to our team!",
				requiredConnectionIds: [1],
			},
			links: [
				{
					connectionId: 1,
					displayName: "Slack",
					providerType: "SLACK",
					teamName: "Engineering",
					required: true,
					available: true,
					linked: false,
				},
				{
					connectionId: 2,
					displayName: "Outline",
					providerType: "OUTLINE",
					required: false,
					available: true,
					linked: false,
				},
			],
		},
	},
};
export const EditAndDiscard: Story = {
	play: async ({ canvas, userEvent }) => {
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Save onboarding settings" }));
		await userEvent.click(canvas.getByRole("switch", { name: "Show a welcome on first visit" }));
		await expect(canvas.getByRole("button", { name: "Save onboarding settings" })).toBeEnabled();
		await userEvent.click(canvas.getByRole("button", { name: "Discard changes" }));
		await expect(
			canvas.getByRole("switch", { name: "Show a welcome on first visit" }),
		).not.toBeChecked();
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Save onboarding settings" }));
	},
};
export const Saving: Story = { args: { saving: true } };
export const SaveFailed: Story = {
	args: { saveError: "Onboarding settings changed. Reload before saving." },
};
export const UnavailableRequirement: Story = {
	args: {
		state: {
			status: "ready",
			settings: { ...settings, requiredConnectionIds: [1] },
			links: [
				{
					connectionId: 1,
					displayName: "Unavailable integration",
					providerType: "UNKNOWN",
					required: true,
					available: false,
					linked: false,
				},
			],
		},
	},
};
export const NarrowViewport: Story = {
	...Configured,
	globals: { viewport: { value: "reflow", isRotated: false } },
	play: async () => {
		await expectNoPageOverflow();
	},
};
