import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import type { ProductFeedbackDestinations } from "@/api/types.gen";
import { hoursBefore, minutesBefore } from "@/stories/story-clock";

import { AdminProductFeedbackDestinationsCard } from "./AdminProductFeedbackDestinationsCard";

const configured = {
	publicRepository: "hephaestus-build/Hephaestus",
	privateRepository: "hephaestus-build/product-feedback",
	llmModelId: 7,
	dailyDraftLimit: 50,
	paused: false,
	noticeVersion: 2,
	publicCheck: "INSTALLED",
	privateCheck: "INSTALLED",
	draftsToday: 12,
	needsAction: 0,
	lastFiledAt: minutesBefore(25),
	oldestWaitingSince: minutesBefore(3),
	snapshotAt: minutesBefore(1),
} satisfies ProductFeedbackDestinations;

const unset = {
	dailyDraftLimit: 50,
	paused: false,
	noticeVersion: 1,
	publicCheck: "UNSET",
	privateCheck: "UNSET",
	draftsToday: 0,
	needsAction: 0,
} satisfies ProductFeedbackDestinations;

const models = [
	{ id: 7, displayName: "Drafting model", connectionDisplayName: "University cluster" },
	{ id: 9, displayName: "Large model", connectionDisplayName: "Cloud provider" },
];

const onSave = fn();

const meta = {
	component: AdminProductFeedbackDestinationsCard,
	args: { state: { status: "ready", destinations: configured, models, saving: false, onSave } },
	tags: ["autodocs"],
} satisfies Meta<typeof AdminProductFeedbackDestinationsCard>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Saving sends every field, and asks everyone to choose again only when the box is ticked. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText("Ready")).toHaveLength(2);
		await expect(canvas.getByText("12 of 50")).toBeVisible();
		await userEvent.click(canvas.getByRole("switch", { name: "Pause" }));
		await userEvent.click(canvas.getByRole("checkbox", { name: "Ask everyone to choose again" }));
		await userEvent.click(canvas.getByRole("button", { name: "Save destinations" }));
		await expect(onSave).toHaveBeenCalledWith({
			publicRepository: "hephaestus-build/Hephaestus",
			privateRepository: "hephaestus-build/product-feedback",
			llmModelId: 7,
			dailyDraftLimit: 50,
			paused: true,
			newNoticeVersion: true,
		});
	},
};

/** A fresh instance: nothing is set, so nothing is checked and no health has been counted. */
export const NotSet: Story = {
	args: { state: { status: "ready", destinations: unset, models, saving: false, onSave } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("Not set")).toBeNull();
		await expect(canvas.getByText("None yet")).toBeVisible();
	},
};

/** Each check says what is wrong with the saved repository, and a changed field waits for the save. */
export const ChecksFailing: Story = {
	args: {
		state: {
			status: "ready",
			destinations: {
				...configured,
				publicCheck: "NOT_PUBLIC",
				privateCheck: "MISSING",
				oldestWaitingSince: hoursBefore(2),
			},
			models,
			saving: false,
			onSave,
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Not public")).toBeVisible();
		await expect(canvas.getByText("Not reachable")).toBeVisible();
		const field = canvas.getByRole("textbox", { name: "Private repository" });
		await userEvent.clear(field);
		await userEvent.type(field, "not a repository");
		await expect(canvas.getByText("Enter the repository as owner/name.")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Save destinations" })).toBeDisabled();
	},
};

export const Saving: Story = {
	args: { state: { status: "ready", destinations: configured, models, saving: true, onSave } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Saving…" })).toBeDisabled();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
};

export const LoadError: Story = {
	args: { state: { status: "error", error: new TypeError("Failed to fetch"), onRetry: fn() } },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("We could not load the product feedback destinations"),
		).toBeVisible();
	},
};
