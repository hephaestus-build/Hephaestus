import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";

import { SidebarProvider, SidebarTrigger } from "@/components/ui/sidebar";
import { expectNoPageOverflow } from "@/test/reflow";

import Header from "./Header";

const meta = {
	component: Header,
	parameters: {
		layout: "fullscreen",
		viewport: { defaultViewport: "desktop" },
	},
	tags: ["autodocs"],
	args: {
		version: "1.0.0",
		environmentName: "Production",
		isProduction: true,
		name: "John Doe",
		username: "johnDoe",
		workspaceSlug: "demo-workspace",
		sidebarTrigger: <SidebarTrigger />,
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

export const Mobile: Story = {
	args: {
		isAuthenticated: true,
		isLoading: false,
	},
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: expectNoPageOverflow,
};
