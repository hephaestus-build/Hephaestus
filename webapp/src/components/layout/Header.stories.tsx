import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { surveyInvitation } from "@/components/product-feedback/fixtures";
import { ProductFeedbackMenu } from "@/components/product-feedback/ProductFeedbackMenu";
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
	argTypes: { feedbackDialog: { control: false }, sidebarTrigger: { control: false } },
	args: {
		version: "1.0.0",
		environmentName: "Production",
		isProduction: true,
		name: "John Doe",
		username: "johnDoe",
		workspaceSlug: "demo-workspace",
		sidebarTrigger: <SidebarTrigger />,
		feedbackDialog: (
			<ProductFeedbackMenu
				invitations={[surveyInvitation]}
				onSendFeedback={fn()}
				onOpenSurvey={fn()}
			/>
		),
		onLogin: fn(),
		onLogout: fn(),
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
		isAuthenticated: true,
		isLoading: false,
	},
};

export const Staging: Story = {
	args: {
		isAuthenticated: true,
		isLoading: false,
		environmentName: "Staging",
		isProduction: false,
	},
};

export const Preview: Story = {
	args: {
		isAuthenticated: true,
		isLoading: false,
		environmentName: "Preview",
		isProduction: false,
	},
};

export const PreviewOfAPullRequest: Story = {
	args: {
		isAuthenticated: true,
		isLoading: false,
		environmentName: "Preview",
		isProduction: false,
		pullRequest: 2042,
	},
	play: async ({ canvas }) => {
		// Every preview is called "Preview", so the pill has to say which pull request it is of and
		// take the reader there. Queried by the visible text, which is what names the link: an
		// accessible name that omits it is one a speech-input user cannot say.
		const link = await canvas.findByRole("link", { name: "Preview · PR #2042" });
		await expect(link).toHaveAttribute(
			"href",
			"https://github.com/hephaestus-build/Hephaestus/pull/2042",
		);
	},
};

export const Development: Story = {
	args: {
		isAuthenticated: true,
		isLoading: false,
		version: "DEV",
		environmentName: "Local",
		isProduction: false,
	},
};

export const LoggedOut: Story = {
	args: {
		isAuthenticated: false,
		isLoading: false,
	},
};

export const Loading: Story = {
	args: {
		isAuthenticated: false,
		isLoading: true,
	},
};

export const NoWorkspace: Story = {
	args: {
		isAuthenticated: true,
		isLoading: false,
		workspaceSlug: undefined,
	},
};

/** Open, so the accessibility check sees links as menu items, which a wrapping link would break. */
export const AccountMenu: Story = {
	args: {
		isAuthenticated: true,
		isLoading: false,
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Account" }));
		const menu = within(await screen.findByRole("menu"));
		const activity = menu.getByRole("menuitem", { name: "Activity" });
		await expectSettledVisible(activity);
		await expect(activity).toHaveAttribute("href", "/w/demo-workspace/activity");
		await expect(menu.getByRole("menuitem", { name: "Settings" })).toHaveAttribute(
			"href",
			"/settings",
		);
	},
};

/** During a user view the menu holds only what acts on the signed-in administrator. */
export const ReadOnly: Story = {
	args: {
		isAuthenticated: true,
		isLoading: false,
		readOnly: true,
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Account" }));
		const menu = within(await screen.findByRole("menu"));
		await expectSettledVisible(menu.getByRole("menuitem", { name: "Sign Out" }));
		await expect(menu.queryByRole("menuitem", { name: "Activity" })).not.toBeInTheDocument();
		await expect(menu.queryByRole("menuitem", { name: "Settings" })).not.toBeInTheDocument();
	},
};

export const Mobile: Story = {
	args: {
		isAuthenticated: true,
		isLoading: false,
	},
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: async ({ canvas }) => {
		await expectNoPageOverflow();
		// Firefox sets this row about 32px wider than Chromium does, so Chromium has to show at least
		// that much room between the logo and the controls: fitting exactly there overflows in Firefox.
		const logo = canvas.getByRole("link", { name: "Hephaestus home" });
		const feedback = canvas.getByRole("button", { name: /^Feedback/u });
		await expect(
			feedback.getBoundingClientRect().left - logo.getBoundingClientRect().right,
		).toBeGreaterThanOrEqual(40);
	},
};
