import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent } from "storybook/test";

import { ResearchParticipationSection } from "./ResearchParticipationSection";

const meta = {
	component: ResearchParticipationSection,
	parameters: {
		layout: "centered",
	},
	tags: ["autodocs"],
	argTypes: {
		participateInResearch: {
			control: "boolean",
			description: "Whether the user participates in research",
		},
		onToggleResearch: {
			description: "Callback when the research participation setting changes",
		},
		isLoading: {
			control: "boolean",
			description: "Whether the component is in loading state",
		},
	},
	args: {
		organization: "AET",
		onToggleResearch: fn(),
		isLoading: false,
	},
} satisfies Meta<typeof ResearchParticipationSection>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Enabled: Story = {
	args: {
		participateInResearch: true,
	},
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("switch", { name: "Participate in academic research" }));
		await expect(args.onToggleResearch).toHaveBeenCalledWith(false, expect.anything());
	},
};

export const NotParticipating: Story = {
	args: {
		participateInResearch: false,
	},
};

export const Loading: Story = {
	args: {
		participateInResearch: true,
		isLoading: true,
	},
};

export const LoadFailure: Story = {
	args: {
		participateInResearch: false,
		isError: true,
		error: new globalThis.Error("Connection unavailable"),
		onRetry: fn(),
	},
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(args.onRetry).toHaveBeenCalledOnce();
	},
};
