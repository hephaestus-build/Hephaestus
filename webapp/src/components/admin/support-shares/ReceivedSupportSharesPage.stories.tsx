import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import { receivedShare, receivedShares } from "@/components/support-share/fixtures";
import { withStandardPage } from "@/stories/decorators";

import { ReceivedSupportSharesPage } from "./ReceivedSupportSharesPage";

const meta = {
	component: ReceivedSupportSharesPage,
	parameters: { layout: "fullscreen" },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		list: { status: "ready", shares: receivedShares },
		reading: { status: "closed" },
		onRead: fn(),
		onClose: fn(),
	},
} satisfies Meta<typeof ReceivedSupportSharesPage>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The list names who shared and until when, and shows no words: a share opens only when read. */
export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const shares = within(canvas.getByRole("list", { name: "Support shares" }));
		await expect(shares.getAllByRole("listitem")).toHaveLength(2);
		await expect(canvas.queryByRole("list", { name: "Shared messages" })).toBeNull();
		await userEvent.click(shares.getAllByRole("button", { name: "Read" })[0] ?? document.body);
		await expect(args.onRead).toHaveBeenCalledWith(receivedShares[0]?.id);
	},
};

/** A share being read shows the person's diagnostics and the messages they chose to share. */
export const Reading: Story = {
	args: { reading: { status: "ready", share: receivedShare } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Shared by Katherine Johnson")).toBeVisible();
		const messages = within(canvas.getByRole("list", { name: "Shared messages" }));
		await expect(messages.getByText("Why does my build fail on the second run?")).toBeVisible();
		await expect(canvas.getByText(/the person sees when you read it/u)).toBeVisible();
	},
};

/** The person deleted or changed the messages since: none of them shows. */
export const MessagesWithheld: Story = {
	args: {
		reading: { status: "ready", share: { ...receivedShare, messages: [], textWithheld: true } },
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("The person deleted or changed these messages since they shared them."),
		).toBeVisible();
		await expect(canvas.queryByRole("list", { name: "Shared messages" })).toBeNull();
	},
};

export const Empty: Story = {
	args: { list: { status: "ready", shares: [] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No one shares a question with you now")).toBeVisible();
	},
};

export const Loading: Story = {
	args: { list: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByLabelText("Loading support shares…")).toBeVisible();
	},
};

export const LoadFailed: Story = {
	args: { list: { status: "error", error: { status: 500 }, onRetry: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load the support shares")).toBeVisible();
	},
};

/** A share revoked or expired a moment ago answers as not found. */
export const ReadFailed: Story = {
	args: { reading: { status: "error", error: { status: 404 }, onRetry: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not read this share")).toBeVisible();
	},
};
