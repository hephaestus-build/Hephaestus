import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";
import { expectNoPageOverflow } from "@/stories/reflow";
import { StatefulPatch } from "@/stories/stateful";
import { expectUnavailable } from "@/test/controls";
import { FeedbackSendingSettings } from "./FeedbackSendingSettings";
import { reviewSettings } from "./review/fixtures";

const settings = reviewSettings({ deliverToMerged: false });

const meta = {
	component: FeedbackSendingSettings,
	parameters: { layout: "padded", chromatic: { viewports: [1440] } },
	tags: ["autodocs"],
	args: {
		settings,
		saving: false,
		onSet: fn(),
		onClearDeliverToMerged: fn(),
	},
	decorators: [
		(Story) => (
			<div className="mx-auto w-full max-w-3xl">
				<Story />
			</div>
		),
	],
	render: (args) => (
		<StatefulPatch initial={args.settings}>
			{(current, patch) => (
				<FeedbackSendingSettings
					{...args}
					settings={current}
					onSet={(update) => {
						args.onSet(update);
						patch(update);
					}}
					onClearDeliverToMerged={() => {
						args.onClearDeliverToMerged();
						patch({ deliverToMergedOverride: null });
					}}
				/>
			)}
		</StatefulPatch>
	),
} satisfies Meta<typeof FeedbackSendingSettings>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Sending: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByRole("heading", { name: "Sending feedback" })).toBeVisible();
		await expect(canvas.getByRole("switch", { name: "Send feedback" })).toBeChecked();
		await expect(canvas.getByText("Default: Off")).toBeVisible();
		await userEvent.click(canvas.getByRole("switch", { name: "Send feedback" }));
		await expect(args.onSet).toHaveBeenCalledWith({ deliveryStatus: "PAUSED" });
		await expect(canvas.getByText("Sending is paused")).toBeVisible();
		await expect(canvas.getByRole("switch", { name: "Send feedback" })).not.toBeChecked();
	},
};

export const SendingPausedInDarkMode: Story = {
	args: { settings: { ...settings, deliveryStatus: "PAUSED" } },
	globals: { theme: "dark" },
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 1440] },
	},
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText("Sending is paused")).toBeVisible();
		// The name stays "Send feedback" in both states; the badge carries the state.
		const send = canvas.getByRole("switch", { name: "Send feedback" });
		await expect(send).not.toBeChecked();
		await expectNoPageOverflow();
		await userEvent.click(send);
		await expect(args.onSet).toHaveBeenCalledWith({ deliveryStatus: "ACTIVE" });
		await expect(canvas.queryByText("Sending is paused")).not.toBeInTheDocument();
		await expect(send).toBeChecked();
	},
};

export const PostAfterMergeSetHere: Story = {
	args: {
		settings: { ...settings, deliverToMerged: true, deliverToMergedOverride: true },
	},
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByRole("switch", { name: "Post feedback after merge" })).toBeChecked();
		await expect(canvas.queryByText(/^Default:/u)).not.toBeInTheDocument();
		await userEvent.click(
			canvas.getByRole("button", { name: "Use default for Post feedback after merge" }),
		);
		await expect(args.onClearDeliverToMerged).toHaveBeenCalledOnce();
		await expect(canvas.getByText("Default: On")).toBeVisible();
	},
};

export const Saving: Story = {
	args: { saving: true },
	play: async ({ canvas }) => {
		await expectUnavailable(canvas.getByRole("switch", { name: "Send feedback" }));
		await expectUnavailable(canvas.getByRole("switch", { name: "Post feedback after merge" }));
	},
};
