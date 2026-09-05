import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

import { Stateful } from "@/stories/stateful";
import { expectSettledVisible } from "@/test/overlay";
import { ConfirmAccessDialog } from "./ConfirmAccessDialog";

const gitlab = {
	registrationId: "gitlab-team",
	providerType: "GITLAB",
	displayName: "Team GitLab",
};
const github = { registrationId: "github", providerType: "GITHUB", displayName: "GitHub" };

const meta = {
	component: ConfirmAccessDialog,
	tags: ["autodocs"],
	args: {
		open: true,
		onOpenChange: fn(),
		onSignIn: fn(),
		onRetry: fn(),
		loading: false,
		error: false,
		maxAgeSeconds: 300,
		providers: [gitlab],
	},
	render: (args) => (
		<Stateful initial={args.open}>
			{(open, setOpen) => (
				<ConfirmAccessDialog
					{...args}
					open={open}
					onOpenChange={(next) => {
						setOpen(next);
						args.onOpenChange(next);
					}}
				/>
			)}
		</Stateful>
	),
} satisfies Meta<typeof ConfirmAccessDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The window arrives in seconds and has to reach the reader as a duration they can act on. */
export const Default: Story = {
	play: async ({ args }) => {
		const dialog = await screen.findByRole("dialog", { name: "Confirm access" });
		await expectSettledVisible(dialog);
		await expect(dialog).toHaveTextContent("sign-in from the last 5 minutes");

		await userEvent.click(screen.getByRole("button", { name: "Continue with Team GitLab" }));
		await expect(args.onSignIn).toHaveBeenCalledWith("gitlab-team");
	},
};

/** With no window named, the ask stays true rather than inventing a number. */
export const NoWindowGiven: Story = {
	args: { maxAgeSeconds: undefined },
	play: async () => {
		const dialog = await screen.findByRole("dialog", { name: "Confirm access" });
		await expect(dialog).toHaveTextContent("This action needs a recent sign-in.");
		await expect(dialog).not.toHaveTextContent("from the last");
	},
};

/** An account may be linked to several registrations of the same provider. */
export const SeveralLinkedRegistrations: Story = {
	args: { providers: [github, gitlab] },
	play: async () => {
		await screen.findByRole("dialog", { name: "Confirm access" });
		await expect(screen.getAllByRole("button", { name: /^Continue with/ })).toHaveLength(2);
	},
};

/** Nothing is clickable until the account's own registrations are known. */
export const Loading: Story = {
	args: { loading: true, providers: [] },
	play: async () => {
		await screen.findByText("Loading sign-in options…");
		await expect(screen.queryByRole("button", { name: /^Continue with/ })).toBeNull();
	},
};

/** The list could not be read, so the dialog says so and offers the retry rather than a dead end. */
export const FailedToLoad: Story = {
	args: { error: true, providers: [] },
	play: async ({ args }) => {
		await expect(await screen.findByRole("alert")).toHaveTextContent("Could not load sign-in");
		await userEvent.click(screen.getByRole("button", { name: "Try again" }));
		await expect(args.onRetry).toHaveBeenCalled();
	},
};

/**
 * The account is linked to nothing this instance still offers. Signing in with a registration the
 * account has never used would resolve a different account, so there is no button to offer.
 */
export const NoLinkedProvider: Story = {
	args: { providers: [] },
	play: async () => {
		const dialog = await screen.findByRole("dialog", { name: "Confirm access" });
		await expect(dialog).toHaveTextContent("Contact your instance operator");
		await expect(screen.queryByRole("button", { name: /^Continue with/ })).toBeNull();
	},
};

/** The dialog is the full viewport at 320px, where a long provider name has to wrap. */
export const LongProviderName: Story = {
	args: {
		providers: [
			{
				registrationId: "gitlab-engineering-europe",
				providerType: "GITLAB",
				displayName: "European engineering and platform operations GitLab",
			},
		],
	},
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		const button = await screen.findByRole("button", {
			name: "Continue with European engineering and platform operations GitLab",
		});
		await expectSettledVisible(button);
		// The label wraps rather than spilling out of the panel: WCAG 2.2 SC 1.4.10 (Reflow).
		await expect(button.scrollWidth).toBeLessThanOrEqual(button.clientWidth);
	},
};

/** A refused action is not a trap: dismissing it leaves the action untouched and the page in place. */
export const Dismiss: Story = {
	play: async ({ args }) => {
		await screen.findByRole("dialog", { name: "Confirm access" });
		await userEvent.keyboard("{Escape}");
		await expect(args.onOpenChange).toHaveBeenCalledWith(false);
	},
};
