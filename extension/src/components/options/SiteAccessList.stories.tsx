import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { SiteAccessList } from "~/components/options/SiteAccessList";
import { expectNoHorizontalOverflow } from "~/stories/reflow";

const meta = {
	component: SiteAccessList,
	tags: ["autodocs"],
	args: {
		state: {
			status: "ready",
			entries: [
				{
					origin: "https://github.com",
					providerType: "GITHUB",
					workspaces: ["Octo team"],
					granted: true,
				},
				{
					origin: "https://gitlab.lrz.de",
					providerType: "GITLAB",
					workspaces: ["Intro Course 2026", "Research pilot"],
					granted: false,
				},
			],
		},
		webAppOrigin: "https://heph.example.test",
		onGrant: fn(),
		onRevoke: fn(),
	},
} satisfies Meta<typeof SiteAccessList>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas, userEvent, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Allow on https://gitlab.lrz.de" }));
		await expect(args.onGrant).toHaveBeenCalledWith("https://gitlab.lrz.de");
		await userEvent.click(
			canvas.getByRole("button", { name: "Remove access to https://github.com" }),
		);
		await expect(args.onRevoke).toHaveBeenCalledWith("https://github.com");
	},
};

export const Empty: Story = {
	args: { state: { status: "ready", entries: [] } },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("heading", { name: "No GitHub or GitLab site to allow yet" }),
		).toBeVisible();
		await expect(canvas.getByRole("link", { name: /Open Hephaestus/u })).toHaveAttribute(
			"href",
			"https://heph.example.test",
		);
	},
};

export const Granting: Story = {
	args: { activity: { origin: "https://gitlab.lrz.de", status: "pending" } },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("button", { name: "Allow on https://gitlab.lrz.de" }),
		).toBeDisabled();
		await expect(
			canvas.getByRole("button", { name: "Remove access to https://github.com" }),
		).toBeEnabled();
	},
};

export const PermissionDenied: Story = {
	args: { activity: { origin: "https://gitlab.lrz.de", status: "denied" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("status")).toHaveTextContent(/Chrome did not allow access/u);
		await expect(
			canvas.getByRole("button", { name: "Allow on https://gitlab.lrz.de" }),
		).toBeEnabled();
	},
};

export const Loading: Story = { args: { state: { status: "loading" } } };

export const Failed: Story = {
	args: { state: { status: "error", message: "Sign in to Hephaestus again.", onRetry: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "Sites could not be loaded" })).toBeVisible();
	},
};

export const Narrow: Story = {
	parameters: { reflow: true },
	args: {
		state: {
			status: "ready",
			entries: [
				{
					origin: "https://gitlab.department-of-informatics.example-university.de",
					providerType: "GITLAB",
					workspaces: ["Software Engineering Practical Course 2026", "Research pilot"],
					granted: false,
				},
			],
		},
	},
	play: async ({ canvasElement }) => {
		await expectNoHorizontalOverflow(canvasElement);
	},
};

export const Dark: Story = {
	globals: { theme: "dark" },
	play: async ({ canvas }) => {
		await expect(document.documentElement).toHaveClass("dark");
		await expect(canvas.getByText("Allowed")).toBeVisible();
	},
};
