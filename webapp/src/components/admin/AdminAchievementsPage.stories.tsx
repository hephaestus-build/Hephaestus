import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";
import { expectGenuinelyDisabled } from "@/test/controls";

import { AdminAchievementsPage } from "./AdminAchievementsPage";

const meta = {
	component: AdminAchievementsPage,
	parameters: { layout: "fullscreen" },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		users: [
			{
				id: 1,
				login: "ada",
				name: "Ada Lovelace",
				hidden: false,
				url: "https://github.com/ada",
				teams: [],
				user: { id: "user-1", login: "ada", name: "Ada Lovelace", email: "ada@example.com" },
			},
		],
		workspaceSlug: "acme",
		isLoading: false,
		isReloading: false,
		isRecalculatingAll: false,
		recalculatingUsers: new Set<string>(),
		onReload: fn(),
		onRecalculateAll: fn(),
		onRecalculate: fn(),
		onRetry: fn(),
	},
} satisfies Meta<typeof AdminAchievementsPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Loading: Story = {
	args: { users: [], isLoading: true },
	play: async ({ canvas }) => {
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Reload Definitions" }));
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Recalculate All" }));
		await expect(canvas.getByText("Loading users...")).toBeVisible();
	},
};

export const Empty: Story = {
	args: { users: [] },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No users found")).toBeVisible();
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Recalculate All" }));
		await expect(canvas.getByRole("button", { name: "Reload Definitions" })).toBeEnabled();
	},
};

export const Error: Story = {
	globals: { theme: "dark" },
	args: { users: [], error: { status: 503, detail: "Achievements are temporarily unavailable." } },
	play: async ({ canvas, args }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent("Couldn't load achievements");
		await expect(canvas.queryByRole("table")).not.toBeInTheDocument();
		const retry = canvas.getByRole("button", { name: /retry/i });
		canvas.getByRole("button", { name: "Reload Definitions" }).focus();
		await userEvent.tab();
		await expect(retry).toHaveFocus();
		await userEvent.keyboard("{Enter}");
		await expect(args.onRetry).toHaveBeenCalledOnce();
	},
};

export const PermissionDenied: Story = {
	args: { users: [], error: { status: 403 } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent("You don't have permission");
		await expect(canvas.queryByRole("button", { name: /retry/i })).not.toBeInTheDocument();
	},
};

export const Reloading: Story = {
	args: { isReloading: true },
	play: async ({ canvas }) => {
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Reloading..." }));
	},
};

export const RecalculatingAll: Story = {
	args: { isRecalculatingAll: true },
	play: async ({ canvas }) => {
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Recalculating All..." }));
	},
};

export const RecalculatingMember: Story = {
	args: { recalculatingUsers: new Set(["ada"]) },
};

export const Reflow: Story = {
	args: {
		users: meta.args.users.map((member) => ({
			...member,
			name: "Alexandra Montgomery-Worthington",
			user: { ...member.user, name: "Alexandra Montgomery-Worthington" },
		})),
	},
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
};
