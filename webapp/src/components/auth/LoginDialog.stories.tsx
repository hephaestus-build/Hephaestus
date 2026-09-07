import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";

import { Stateful } from "@/stories/stateful";

import { LoginDialog } from "./LoginDialog";

const onRetry = fn();

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
export const Loading: Story = { args: { options: { status: "loading" } } };
export const Empty: Story = { args: { options: { status: "ready", providers: [] } } };
export const Failed: Story = {
	args: { options: { status: "error", onRetry } },
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await userEvent.click(dialog.getByRole("button", { name: "Try again" }));
		await expect(onRetry).toHaveBeenCalled();
	},
};
export const ChooseProvider: Story = {
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("dialog"));
		await expect(dialog.getByRole("link", { name: /privacy notice/i })).toHaveAttribute(
			"href",
			"/privacy",
		);
		await userEvent.click(dialog.getByRole("button", { name: "Continue with GitLab" }));
		await expect(args.onSignIn).toHaveBeenCalledWith("gitlab");
	},
};
export const Dismiss: Story = {
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("dialog"));
		await userEvent.click(dialog.getByRole("button", { name: "Close" }));
		await expect(args.onClose).toHaveBeenCalled();
		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
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
