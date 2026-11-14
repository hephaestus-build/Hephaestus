import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { expectNoPageOverflow } from "@/stories/reflow";

import {
	GitLabWizardSkeleton,
	InstallActionSkeleton,
	ProviderChoiceSkeleton,
} from "./CreateWorkspaceSkeletons";

/**
 * What the workspace creation pages show while the provider options are read. Each is the outline
 * of what replaces it, not a spinner in a fixed box, so the page does not jump on arrival. They
 * show nothing for the first second, so a quick read flashes nothing.
 */
const meta = {
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	decorators: [
		(Story) => (
			<div className="mx-auto w-full max-w-2xl">
				<Story />
			</div>
		),
	],
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

/** Past the one second they wait for. */
const SHOWN = { timeout: 3000 };

/** The two provider cards, in the grid that the real cards take. */
export const ProviderChoice: Story = {
	render: () => <ProviderChoiceSkeleton />,
	play: async ({ canvas }) => {
		// A read that answers within a second shows no stand-in at all.
		await expect(canvas.queryByText("Loading the providers…")).toBeNull();
		await expect(await canvas.findByText("Loading the providers…", undefined, SHOWN)).toBeVisible();
	},
};

/** The full-width install button of the GitHub page. */
export const InstallAction: Story = {
	render: () => <InstallActionSkeleton />,
	play: async ({ canvas }) => {
		await expect(
			await canvas.findByText("Loading the GitHub App link…", undefined, SHOWN),
		).toBeVisible();
	},
};

/** The GitLab wizard's first step: heading, progress, instance and token fields. */
export const GitLabWizard: Story = {
	render: () => <GitLabWizardSkeleton />,
	play: async ({ canvas }) => {
		await expect(
			await canvas.findByText("Loading the GitLab setup…", undefined, SHOWN),
		).toBeVisible();
	},
};

export const GitLabWizardDark: Story = {
	...GitLabWizard,
	globals: { theme: "dark" },
};

/** In a 320 px window no outline pushes the page wider than the window. */
export const GitLabWizardOnAPhone: Story = {
	...GitLabWizard,
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		await canvas.findByText("Loading the GitLab setup…", undefined, SHOWN);
		await expectNoPageOverflow();
	},
};
