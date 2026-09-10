import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import type { WorkspaceOnboarding } from "@/api/types.gen";
import { expectGenuinelyDisabled, expectUnavailable } from "@/test/controls";
import { expectNoPageOverflow } from "@/test/reflow";
import { WorkspaceOnboardingPage } from "./WorkspaceOnboardingPage";

const welcome = {
	workspaceName: "Engineering",
	enabled: true,
	aiChoiceRequired: true,
	needsWelcome: true,
	completed: false,
	revision: 2,
	welcomeMarkdown:
		"We learn from the work we already do. Start with your team's practices, then explore the feedback on your own practice page.\n\nQuestions? Ask a workspace owner — you can change your preferences at any time.",
	aiOptions: [
		{ choice: "ON_PREMISES", practiceReviewsReady: true, mentorReady: true },
		{ choice: "PRIVATE_CLOUD", practiceReviewsReady: true, mentorReady: true },
	],
	links: [
		{
			connectionId: 1,
			displayName: "Slack",
			providerType: "SLACK",
			registrationId: "slack",
			teamName: "Engineering team",
			required: true,
			available: true,
			linked: false,
		},
		{
			connectionId: 2,
			displayName: "Outline",
			providerType: "OUTLINE",
			registrationId: "outline",
			required: false,
			available: true,
			linked: false,
		},
	],
} satisfies WorkspaceOnboarding;

const meta = {
	title: "Workspace/Member onboarding",
	component: WorkspaceOnboardingPage,
	tags: ["autodocs"],
	parameters: { layout: "padded", chromatic: { viewports: [320, 1440] } },
	args: {
		state: { status: "ready", data: welcome },
		onChoose: fn(),
		onLink: fn(),
		onRefresh: fn(),
		onComplete: fn(),
		onDismiss: fn(),
	},
} satisfies Meta<typeof WorkspaceOnboardingPage>;
export default meta;
type Story = StoryObj<typeof meta>;

export const FirstVisit: Story = {
	play: async ({ canvas, userEvent, args }) => {
		await expect(canvas.getByRole("radio", { name: "On-premises" })).not.toBeChecked();
		await expect(canvas.getByRole("radio", { name: "Private cloud" })).not.toBeChecked();
		await expect(canvas.getByRole("radio", { name: "No AI" })).not.toBeChecked();
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Save AI preference" }));
		await userEvent.click(canvas.getByRole("radio", { name: "No AI" }));
		await expect(canvas.getByRole("radio", { name: "No AI" })).toBeChecked();
		await userEvent.click(canvas.getByRole("button", { name: "Save AI preference" }));
		await expect(args.onChoose).toHaveBeenCalledWith("NO_AI");
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Continue to workspace" }));
	},
};
export const Empty: Story = {
	args: {
		state: { status: "ready", data: { ...welcome, welcomeMarkdown: "", links: [], aiOptions: [] } },
	},
};
export const Loading: Story = { args: { state: { status: "loading" } } };
export const Error: Story = {
	args: { state: { status: "error", error: new globalThis.Error("Unavailable"), onRetry: fn() } },
};
export const NoAiSaved: Story = {
	args: {
		state: {
			status: "ready",
			data: {
				...welcome,
				aiChoice: "NO_AI",
				completed: true,
				needsWelcome: false,
				links: welcome.links.map((link) => ({ ...link, linked: true })),
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("radio", { name: "No AI" })).toBeChecked();
		await expect(canvas.getByRole("button", { name: "Continue to workspace" })).toBeEnabled();
	},
};
export const RequiredLinkUnavailable: Story = {
	args: {
		state: {
			status: "ready",
			data: {
				...welcome,
				aiChoice: "NO_AI",
				links: [
					{
						...welcome.links[0],
						connectionId: 1,
						displayName: "Slack",
						providerType: "SLACK",
						available: false,
						linked: false,
						required: true,
					},
				],
			},
		},
	},
};
export const SavedLocationUnavailable: Story = {
	args: {
		state: {
			status: "ready",
			data: { ...welcome, aiChoice: "ON_PREMISES", aiOptions: [], links: [] },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("radio", { name: "On-premises" })).toBeChecked();
		await expectUnavailable(canvas.getByRole("radio", { name: "Private cloud" }));
		await expect(canvas.getByRole("radio", { name: "No AI" })).toBeEnabled();
	},
};
export const Saving: Story = { args: { pending: "choice" } };
export const SaveFailed: Story = {
	args: {
		saveError: "This AI location is no longer available. Your saved preference has not changed.",
	},
};
export const NarrowViewport: Story = {
	args: {
		state: {
			status: "ready",
			data: { ...welcome, workspaceName: "International engineering and research collaboration" },
		},
	},
	globals: { viewport: { value: "reflow", isRotated: false } },
	play: async () => {
		await expectNoPageOverflow();
	},
};
