import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { daysAfter, daysBefore } from "@/stories/story-clock";

import { problemType } from "@/lib/problem-detail";
import {
	InstanceResearchDisclosureList,
	type ResearchOpenDataset,
} from "./InstanceResearchDisclosureList";

const withdrawn: ResearchOpenDataset = {
	datasetId: "6f1c2b8e-4d3a-4f6b-9a1e-2c7d8e9f0a1b",
	workspaceLabel: "Intro Course",
	createdAt: daysBefore(40),
	useUntil: daysAfter(300),
	useExpired: false,
	removals: [
		{
			participantKey: "p_abcdefghijklmnopqrstuvwxyz",
			reason: "CONSENT_WITHDRAWN",
			requiredAt: daysBefore(3),
		},
		{
			participantKey: "p_zyxwvutsrqponmlkjihgfedcb",
			reason: "ACCOUNT_ERASED",
			requiredAt: daysBefore(1),
		},
	],
};

const expired: ResearchOpenDataset = {
	datasetId: "0b9a8c7d-6e5f-4a3b-8c2d-1e0f9a8b7c6d",
	workspaceLabel: "Design Lab",
	createdAt: daysBefore(400),
	useUntil: daysBefore(5),
	useExpired: true,
	removals: [],
};

const meta = {
	component: InstanceResearchDisclosureList,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		state: { status: "ready", revision: 1, datasets: [withdrawn, expired] },
		onCheck: fn(),
		onRecordRemoved: fn(),
		onClose: fn(),
	},
} satisfies Meta<typeof InstanceResearchDisclosureList>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Owed removals name the key and the reason; an ended use period asks for closure. */
export const OwedRemovalsAndEndedUse: Story = {
	play: async ({ canvas, args }) => {
		await expect(canvas.getByText("Withdrew consent")).toBeVisible();
		await expect(canvas.getByText("Account erased")).toBeVisible();
		await expect(canvas.getByText("The use period ended")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Record removal…" }));
		const dialog = within(await screen.findByRole("alertdialog"));
		await expect(
			dialog.getByRole("checkbox", { name: "p_abcdefghijklmnopqrstuvwxyz" }),
		).toBeChecked();
		await userEvent.click(dialog.getByRole("button", { name: "Cancel" }));
		await expect(args.onRecordRemoved).not.toHaveBeenCalled();
	},
};

/** Nothing is read until the operator checks; each check loads the current records. */
export const NotCheckedYet: Story = {
	args: { state: { status: "unchecked" } },
	play: async ({ canvas, args }) => {
		await expect(canvas.getByText(/Each check loads the current records/u)).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Check open datasets" }));
		await expect(args.onCheck).toHaveBeenCalledOnce();
	},
};

export const Checking: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Checking…" })).toBeDisabled();
	},
};

export const NoOpenDatasets: Story = {
	args: { state: { status: "ready", revision: 1, datasets: [] } },
};

/** A refused check shows no earlier list. */
export const CheckRefused: Story = {
	args: {
		state: {
			status: "error",
			error: { status: 403, type: problemType("passkey-required"), title: "Passkey required" },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not check the open datasets")).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Close dataset…" })).toBeNull();
	},
};

/** A failed attestation names its uncertain outcome, not a refusal. */
export const AttestationUnconfirmed: Story = {
	args: { actionError: { status: 502, title: "Bad gateway" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not confirm whether this was recorded")).toBeVisible();
		await expect(canvas.getByText(/Record it again only if it is still owed there/u)).toBeVisible();
	},
};

export const ClosureInFlight: Story = {
	args: { pendingDatasetId: expired.datasetId },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Recording…" })).toBeDisabled();
	},
};
