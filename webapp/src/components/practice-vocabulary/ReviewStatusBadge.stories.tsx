import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, screen, userEvent, waitFor, within } from "storybook/test";

import { settledPopup } from "@/stories/overlay";

import { statusValues } from "@/components/common/status-def";
import {
	OWN_REVIEW_STATUS_DEFS,
	REVIEW_STATUS_DEFS,
	REVIEW_STOP_REASON_SENTENCES,
} from "./review-status-defs";
import { ReviewStatusBadge } from "./ReviewStatusBadge";

/**
 * A review's status as one label on the workspace admin's list of reviews and on the developer's
 * reviews of their own work: the registry's badge made focusable, so its sentence is reachable by
 * keyboard, pointer and touch.
 */
const meta = {
	component: ReviewStatusBadge,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { def: OWN_REVIEW_STATUS_DEFS.PARTIAL },
} satisfies Meta<typeof ReviewStatusBadge>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The developer's partial review: the sentence says what its results cover. */
export const Partial: Story = {
	play: async ({ canvas }) => {
		await userEvent.tab();
		const badge = canvas.getByRole("button", { name: "Partial" });
		await expect(badge).toHaveFocus();
		await expect(badge).toHaveAccessibleDescription(OWN_REVIEW_STATUS_DEFS.PARTIAL.description);
		await expect(await settledPopup()).toHaveTextContent(
			"Partial · The review’s observations cover only the work it reached.",
		);
	},
};

/** A tap opens the sentence too, so a touch screen reaches it. */
export const OpenedByTap: Story = {
	play: async ({ canvas }) => {
		const badge = canvas.getByRole("button", { name: "Partial" });
		await userEvent.pointer({ keys: "[TouchA]", target: badge });
		const dialog = await screen.findByRole("dialog", { name: "Partial" });
		await waitFor(async () =>
			expect(
				within(dialog).getByText(/The review’s observations cover only the work it reached\./u),
			).toBeVisible(),
		);
	},
};

/** Every status the developer can see carries its own sentence, not only the exceptions. */
export const EveryStatus: Story = {
	render: () => (
		<div className="flex flex-wrap gap-2">
			{statusValues(OWN_REVIEW_STATUS_DEFS).map((status) => (
				<ReviewStatusBadge key={status} def={OWN_REVIEW_STATUS_DEFS[status]} />
			))}
		</div>
	),
	play: async ({ canvas }) => {
		for (const status of statusValues(OWN_REVIEW_STATUS_DEFS)) {
			const def = OWN_REVIEW_STATUS_DEFS[status];
			await expect(canvas.getByRole("button", { name: def.label })).toHaveAccessibleDescription(
				def.description,
			);
		}
	},
};

/** On the workspace admin's list, the recorded stop reason follows the status's sentence. */
export const WithStopReason: Story = {
	args: {
		def: REVIEW_STATUS_DEFS.FAILED,
		reason: REVIEW_STOP_REASON_SENTENCES.TIMED_OUT,
	},
	play: async ({ canvas }) => {
		await userEvent.tab();
		await expect(canvas.getByRole("button", { name: "Failed" })).toHaveFocus();
		await expect(await settledPopup()).toHaveTextContent(
			"Failed · It stopped on an error. Observations it recorded before then still stand. It ran past the time it is allowed and was stopped.",
		);
	},
};
