import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import type { UserViewUser } from "@/api/types.gen";
import { expectNoPageOverflow } from "@/test/reflow";

import { UserViewUsersTable } from "./UserViewUsersTable";

const linked: UserViewUser = {
	userId: 10,
	login: "alex",
	name: "Alex Rivera",
	accountId: 1,
	accountStatus: "ACTIVE",
};
const accountless: UserViewUser = { userId: 11, login: "sam", name: "Sam" };
const suspended: UserViewUser = {
	userId: 12,
	login: "kim",
	name: "Kim Park",
	accountId: 2,
	accountStatus: "SUSPENDED",
};

const retry = fn();

const meta = {
	component: UserViewUsersTable,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		state: { status: "ready", users: [linked, accountless], totalPages: 1 },
		page: 0,
		onPageChange: fn(),
		onView: fn(),
	},
} satisfies Meta<typeof UserViewUsersTable>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas }) => {
		await expect(canvas.getByText("No linked account")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "View as user: sam" }));
		await expect(args.onView).toHaveBeenCalledWith(accountless);
	},
};

export const AccountUnavailable: Story = {
	args: { state: { status: "ready", users: [suspended], totalPages: 1 } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Account unavailable")).toBeVisible();
	},
};

export const LongName: Story = {
	args: {
		state: {
			status: "ready",
			users: [
				{
					...linked,
					name: "Maximilian Alexander von Hohenzollern-Sigmaringen-Weiler",
					login: "maximilian-alexander-von-hohenzollern-sigmaringen-weiler",
				},
			],
			totalPages: 1,
		},
	},
};

export const Paged: Story = {
	args: { state: { status: "ready", users: [linked, accountless], totalPages: 4 }, page: 1 },
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
	args: { state: { status: "ready", users: [linked, accountless, suspended], totalPages: 3 } },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: expectNoPageOverflow,
};
