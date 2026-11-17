import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { ConversationPanel } from "@/components/conversation/ConversationPanel";
import { ProductFeedbackButton } from "@/components/product-feedback/ProductFeedbackButton";
import { SidebarProvider, SidebarTrigger } from "@/components/ui/sidebar";
import { expectSettledVisible } from "@/stories/overlay";
import { expectNoPageOverflow } from "@/stories/reflow";

import Header from "./Header";

const meta = {
	component: Header,
	parameters: {
		layout: "fullscreen",
		viewport: { defaultViewport: "desktop" },
	},
	tags: ["autodocs"],
	argTypes: {
		feedbackDialog: { control: false },
		conversation: { control: false },
		sidebarTrigger: { control: false },
	},
	args: {
		version: "1.0.0",
		environmentName: "Production",
		isProduction: true,
		name: "John Doe",
		username: "johnDoe",
		workspaceSlug: "demo-workspace",
		sidebarTrigger: <SidebarTrigger />,
		feedbackDialog: <ProductFeedbackButton onSendFeedback={fn()} />,
		conversation: (
			<ConversationPanel onOpenInConversation={fn()}>
				<p className="p-4">Your conversation</p>
			</ConversationPanel>
		),
		onLogin: fn(),
		onLogout: fn(),
		onRetrySession: fn(),
	},
	decorators: [
		(Story) => (
			<SidebarProvider>
				<div className="w-full">
					<Story />
				</div>
			</SidebarProvider>
		),
	],
} satisfies Meta<typeof Header>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	args: {
		session: "signed-in",
	},
};

export const Staging: Story = {
	args: {
		session: "signed-in",
		environmentName: "Staging",
		isProduction: false,
	},
};

export const Development: Story = {
	args: {
		session: "signed-in",
		version: "DEV",
		environmentName: "Local",
		isProduction: false,
	},
};

export const LoggedOut: Story = {
	args: {
		session: "signed-out",
	},
};

/** The server cannot say who is signed in, as during a restart: no sign-in is offered. */
export const Unavailable: Story = {
	args: {
		session: "unavailable",
		sidebarTrigger: undefined,
	},
	play: async ({ args, canvas }) => {
		canvas.getByText("Hephaestus is not reachable right now");
		await expect(canvas.queryByRole("button", { name: "Sign in" })).toBeNull();
		await userEvent.click(canvas.getByRole("button", { name: "Try again" }));
		await expect(args.onRetrySession).toHaveBeenCalledOnce();
	},
};

/** The message and its retry fit beside the controls on the narrowest screen. */
export const UnavailableMobile: Story = {
	args: {
		session: "unavailable",
		sidebarTrigger: undefined,
	},
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: async ({ canvas }) => {
		await expectNoPageOverflow();
		await expect(canvas.getByText("Not reachable")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeVisible();
	},
};

export const Loading: Story = {
	args: {
		session: "loading",
	},
};

export const NoWorkspace: Story = {
	args: {
		session: "signed-in",
		workspaceSlug: undefined,
	},
};

/**
 * Open, so the accessibility check sees links as menu items, which a wrapping link would break.
 * The menu holds account tasks only: Activity is in the sidebar, and each place has one name.
 */
export const AccountMenu: Story = {
	args: {
		session: "signed-in",
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("button", { name: "Send feedback about Hephaestus" }),
		).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: /^Account/u }));
		const menu = within(await screen.findByRole("menu"));
		const settings = menu.getByRole("menuitem", { name: "User settings" });
		await expectSettledVisible(settings);
		await expect(settings).toHaveAttribute("href", "/settings?chromeWorkspace=demo-workspace");
		await expect(menu.getByRole("menuitem", { name: "Your reports" })).toHaveAttribute(
			"href",
			"/product-feedback?chromeWorkspace=demo-workspace",
		);
		await expect(menu.getAllByRole("menuitem").map((item) => item.textContent)).toEqual([
			"User settings",
			"Your reports",
			"Sign out",
		]);
	},
};

/**
 * Get started, after the reader hid it or finished it: the account menu shows it in the corner again.
 * It is the first account task, so a reader who hid the list finds it where the toast said.
 */
export const GetStartedInMenu: Story = {
	args: {
		session: "signed-in",
		onShowGetStarted: fn(),
	},
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /^Account/u }));
		const menu = within(await screen.findByRole("menu"));
		const getStarted = menu.getByRole("menuitem", { name: "Get started" });
		await expectSettledVisible(getStarted);
		await expect(menu.getAllByRole("menuitem").map((item) => item.textContent)).toEqual([
			"Get started",
			"User settings",
			"Your reports",
			"Sign out",
		]);
		await userEvent.click(getStarted);
		await expect(args.onShowGetStarted).toHaveBeenCalledOnce();
	},
};

/** During a user view the menu holds only what acts on the signed-in administrator. */
export const ReadOnly: Story = {
	args: {
		session: "signed-in",
		readOnly: true,
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /^Account/u }));
		const menu = within(await screen.findByRole("menu"));
		await expectSettledVisible(menu.getByRole("menuitem", { name: "Sign out" }));
		await expect(menu.queryByRole("menuitem", { name: "User settings" })).not.toBeInTheDocument();
		await expect(menu.queryByRole("menuitem", { name: "Your reports" })).not.toBeInTheDocument();
	},
};

export const Mobile: Story = {
	args: {
		session: "signed-in",
	},
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: async ({ canvas }) => {
		await expectNoPageOverflow();
		// Firefox lays this row out wider than Chromium does, so Chromium has to show room to spare
		// between the logo and the controls: a row that fits exactly here overflows there.
		const logo = canvas.getByRole("link", { name: "Hephaestus home" });
		const feedback = canvas.getByRole("button", { name: "Send feedback about Hephaestus" });
		await expect(canvas.getByRole("button", { name: "Ask Hephaestus" })).toBeVisible();
		await expect(
			feedback.getBoundingClientRect().left - logo.getBoundingClientRect().right,
		).toBeGreaterThanOrEqual(40);
	},
};
