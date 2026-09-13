import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent } from "storybook/test";

import { expectUnavailable } from "@/test/controls";

import { Stateful } from "@/stories/stateful";

import { EmailPreferencesSection } from "./EmailPreferencesSection";

const ready = {
	status: "ready",
	preferences: {
		productFeedback: false,
		workspaceAlerts: false,
		surveySummaries: false,
		productFeedbackFrequency: "IMMEDIATE",
		productSurveys: false,
		researchSurveys: false,
		emailAvailable: true,
	},
	isPending: false,
	onChange: fn(),
} as const;

const meta = {
	component: EmailPreferencesSection,
	tags: ["autodocs"],
	args: { state: ready, isAppAdmin: false },
	render: (args) => {
		const state = args.state;
		if (state.status !== "ready") return <EmailPreferencesSection {...args} />;
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
		const product = canvas.getByRole("switch", { name: "Product survey invitations" });
		const research = canvas.getByRole("switch", { name: "Research survey invitations" });
		await expect(product).not.toBeChecked();
		await expect(research).not.toBeChecked();
		await userEvent.click(product);
		await expect(product).toBeChecked();
		await expect(research).not.toBeChecked();
		await userEvent.click(product);
		await expect(product).not.toBeChecked();
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
				productFeedbackFrequency: "DAILY",
			},
		},
	},
};

export const Subscribed: Story = {
	args: {
		state: {
			...ready,
			preferences: { ...ready.preferences, productSurveys: true, researchSurveys: true },
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
				productSurveys: true,
				researchSurveys: true,
			},
		},
	},
	play: async ({ canvas }) => {
		const product = canvas.getByRole("switch", { name: "Product survey invitations" });
		await expectUnavailable(canvas.getByRole("switch", { name: "Workspace connection alerts" }));
		await userEvent.click(product);
		await expect(product).not.toBeChecked();
		await expectUnavailable(product);
		await expect(canvas.getByRole("switch", { name: "Research survey invitations" })).toBeChecked();
	},
};

export const Saving: Story = {
	args: { state: { ...ready, isPending: true } },
	play: async ({ canvas }) => {
		await expectUnavailable(canvas.getByRole("switch", { name: "Product survey invitations" }));
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
