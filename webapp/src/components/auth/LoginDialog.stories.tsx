import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { Stateful } from "@/stories/stateful";
import { expectDismissed } from "@/test/overlay";

import { LoginDialog } from "./LoginDialog";

const meta = {
	component: LoginDialog,
	args: {
		open: true,
		onClose: fn(),
		onSignIn: fn(),
		options: {
			status: "ready",
			providers: [
				{ registrationId: "github", displayName: "GitHub", providerType: "GITHUB" },
				{ registrationId: "gitlab", displayName: "GitLab", providerType: "GITLAB" },
			],
		},
	},
	render: (args) => (
		<Stateful initial={args.open}>
			{(open, setOpen) => (
				<LoginDialog
					{...args}
					open={open}
					onClose={() => {
						args.onClose();
						setOpen(false);
					}}
				/>
			)}
		</Stateful>
	),
	parameters: { layout: "fullscreen" },
} satisfies Meta<typeof LoginDialog>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Default: Story = {};
/** The dialog holds the providers and nothing else; where a press leads is the route's contract. */
export const ChooseProvider: Story = {
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await expect(dialog.getByRole("button", { name: "Continue with GitLab" })).toBeEnabled();
	},
};
export const Dismiss: Story = {
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await userEvent.click(dialog.getByRole("button", { name: "Close" }));
		await expectDismissed();
	},
};
export const Narrow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
};

export const LongProviderName: Story = {
	args: {
		options: {
			status: "ready",
			providers: [
				{
					registrationId: "university-gitlab",
					displayName: "Technical University of Munich — Research GitLab",
					providerType: "GITLAB",
				},
			],
		},
	},
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
};
