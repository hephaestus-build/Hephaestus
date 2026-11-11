import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import type { AccountRef, UserViewAccount } from "@/api/types.gen";
import { expectNoPageOverflow } from "@/stories/reflow";

import { UserViewUsersTable } from "./UserViewUsersTable";

function viewable(
	accountId: string,
	providerLogin: string | null,
	account: Partial<AccountRef>,
): UserViewAccount {
	return {
		accountId,
		providerLogin,
		account: {
			id: accountId,
			status: "ACTIVE",
			displayName: null,
			avatarUrl: null,
			identities: [],
			...account,
		},
	};
}

const linked = viewable("10", "alex", {
	displayName: "Alex Rivera",
	identities: [
		{ provider: "GITHUB", login: "alex", displayName: "Alex Rivera", avatarUrl: null },
		{ provider: "GITLAB", login: "arivera", displayName: null, avatarUrl: null },
	],
});
const unclaimed = viewable("11", "sam", {
	status: "UNCLAIMED",
	identities: [{ provider: "GITHUB", login: "sam", displayName: "Sam", avatarUrl: null }],
});
const blocked = viewable("12", "kim", { status: "BLOCKED", displayName: "Kim Park" });

const retry = fn();

const meta = {
	component: UserViewUsersTable,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		state: { status: "ready", users: [linked, unclaimed], totalPages: 1 },
		page: 0,
		onPageChange: fn(),
		onView: fn(),
	},
} satisfies Meta<typeof UserViewUsersTable>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas }) => {
		await expect(canvas.getByText("Unclaimed")).toBeVisible();
		await expect(canvas.getAllByRole("button", { name: /^View as user/u })).toHaveLength(2);
		await userEvent.click(canvas.getByRole("button", { name: "View as user: Sam" }));
		await expect(args.onView).toHaveBeenCalledWith(unclaimed);
	},
};

export const AccountUnavailable: Story = {
	args: { state: { status: "ready", users: [blocked], totalPages: 1 } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Blocked")).toBeVisible();
	},
};

export const LongName: Story = {
	args: {
		state: {
			status: "ready",
			users: [
				viewable("13", "maximilian-alexander-von-hohenzollern-sigmaringen-weiler", {
					displayName: "Maximilian Alexander von Hohenzollern-Sigmaringen-Weiler",
				}),
			],
			totalPages: 1,
		},
	},
};

export const Paged: Story = {
	args: { state: { status: "ready", users: [linked, unclaimed], totalPages: 4 }, page: 1 },
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Go to next page" }));
		await expect(args.onPageChange).toHaveBeenCalledWith(2);
	},
};

export const Empty: Story = {
	args: { state: { status: "ready", users: [], totalPages: 0 } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No users to view")).toBeVisible();
	},
};

export const Loading: Story = { args: { state: { status: "loading" } } };

export const LoadFailed: Story = {
	args: {
		state: {
			status: "error",
			error: { status: 503, detail: "User view audit is unavailable" },
			onRetry: retry,
		},
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(retry).toHaveBeenCalledOnce();
	},
};

export const Reflow: Story = {
	args: { state: { status: "ready", users: [linked, unclaimed, blocked], totalPages: 3 } },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: expectNoPageOverflow,
};
