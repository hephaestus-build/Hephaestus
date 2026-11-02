import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent } from "storybook/test";

import { expectUnavailable } from "@/test/controls";

import { Stateful } from "@/stories/stateful";

import { EmailPreferencesSection } from "./EmailPreferencesSection";

const ready = {
	status: "ready",
	preferences: {
		productFeedback: false,
		productFeedbackUpdates: true,
		workspaceAlerts: false,
		emailAvailable: true,
		deliveryConfigured: true,
		weeklyDigest: false,
		weeklyDigestAvailable: true,
	},
	isPending: false,
	onChange: fn(),
} as const;

const meta = {
	component: EmailPreferencesSection,
	tags: ["autodocs"],
	args: { state: ready, isAppAdmin: false },
	render: (args) => {
		const { state } = args;
		if (state.status !== "ready") {
			return <EmailPreferencesSection {...args} />;
		}
		return (
			<Stateful initial={state.preferences}>
				{(preferences, setPreferences) => (
					<EmailPreferencesSection
						{...args}
						state={{
							...state,
							preferences,
							onChange: (next) => {
								state.onChange(next);
								setPreferences({ ...preferences, ...next });
							},
						}}
					/>
				)}
			</Stateful>
		);
	},
} satisfies Meta<typeof EmailPreferencesSection>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		const product = canvas.getByRole("switch", { name: "Workspace connection alerts" });
		await expect(product).not.toBeChecked();
		await userEvent.click(product);
		await expect(product).toBeChecked();
		await expect(ready.onChange).toHaveBeenCalledWith({ workspaceAlerts: true });
		await userEvent.click(product);
		await expect(product).not.toBeChecked();
	},
};

export const ReporterUpdates: Story = {
	play: async ({ canvas }) => {
		const updates = canvas.getByRole("switch", { name: "Your product feedback updates" });
		await expect(updates).toBeChecked();
		await userEvent.click(updates);
		await expect(updates).not.toBeChecked();
		await expect(ready.onChange).toHaveBeenCalledWith({ productFeedbackUpdates: false });
		await userEvent.click(updates);
		await expect(updates).toBeChecked();
		await expect(ready.onChange).toHaveBeenCalledWith({ productFeedbackUpdates: true });
	},
};

export const InstanceAdmin: Story = {
	args: {
		isAppAdmin: true,
		state: {
			...ready,
			preferences: {
				...ready.preferences,
				productFeedback: true,
			},
		},
	},
};

export const FormerInstanceAdmin: Story = {
	args: {
		state: {
			...ready,
			preferences: {
				...ready.preferences,
				emailAvailable: false,
				productFeedback: true,
			},
		},
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("switch", { name: "New product feedback" }));
		await expect(canvas.queryByRole("switch", { name: "New product feedback" })).toBeNull();
	},
};

export const Subscribed: Story = {
	args: {
		state: {
			...ready,
			preferences: { ...ready.preferences, workspaceAlerts: true },
		},
	},
};

export const NoVerifiedAddress: Story = {
	args: {
		state: {
			...ready,
			preferences: {
				...ready.preferences,
				emailAvailable: false,
			},
		},
	},
	play: async ({ canvas }) => {
		const product = canvas.getByRole("switch", { name: "Workspace connection alerts" });
		await userEvent.click(product);
		await expect(product).not.toBeChecked();
		await expectUnavailable(product);
	},
};

export const DeliveryNotConfigured: Story = {
	args: {
		state: {
			...ready,
			preferences: {
				...ready.preferences,
				deliveryConfigured: false,
				productFeedbackUpdates: false,
			},
		},
	},
};

export const RetainedSubscriptions: Story = {
	args: {
		isAppAdmin: true,
		state: {
			...ready,
			preferences: {
				...ready.preferences,
				deliveryConfigured: false,
				emailAvailable: false,
				productFeedback: true,
			},
		},
	},
};

export const Saving: Story = {
	args: { state: { ...ready, isPending: true } },
	play: async ({ canvas }) => {
		await expectUnavailable(canvas.getByRole("switch", { name: "Workspace connection alerts" }));
		await expect(
			await canvas.findByText("Saving email choices…", {}, { timeout: 2000 }),
		).toHaveAttribute("role", "status");
	},
};

export const Loading: Story = { args: { state: { status: "loading" } } };

export const LoadError: Story = {
	args: {
		state: { status: "error", error: new Error("Email preferences unavailable"), onRetry: fn() },
	},
};

export const NarrowDark: Story = {
	args: {
		isAppAdmin: true,
		state: { ...ready, preferences: { ...ready.preferences, emailAvailable: false } },
	},
	globals: { viewport: { value: "reflow" }, theme: "dark" },
};
