import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { RecordTeamDecisionForm } from "./RecordTeamDecisionForm";

const DRAFT =
	"We will check the step-free route to the return point, because the map currently assumes it exists. Next review: look for a route observation or an explicit unknown.";

const meta = {
	component: RecordTeamDecisionForm,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		problemSummary:
			"The map assumes a step-free route to the return point, with no evidence for it.",
		teamName: "Wayfinding",
		onRecord: fn(),
	},
	decorators: [
		(Story) => (
			<div className="max-w-xl">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof RecordTeamDecisionForm>;

export default meta;
type Story = StoryObj<typeof meta>;

/** No prompt and no draft: a state, an empty note, and who will see the record. */
export const Default: Story = {
	play: async ({ args, canvas }) => {
		await expect(canvas.getByRole("radio", { name: /^Open/u })).toBeChecked();
		await expect(canvas.getByRole("textbox", { name: "Note" })).not.toHaveAttribute("placeholder");
		await expect(canvas.getByText(/sees this record and your name on it/u)).toHaveTextContent(
			"Every current member of Team Wayfinding sees this record and your name on it.",
		);
		await expect(args.onRecord).not.toHaveBeenCalled();
	},
};

/** The answer left a real choice: the empty note shows the optional prompt as its placeholder, and nothing more. */
export const EmptyNoteWithPrompt: Story = {
	args: { prompt: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("textbox", { name: "Note" })).toHaveAttribute(
			"placeholder",
			"What will you try, why, and what would you look for in the next version?",
		);
		await expect(canvas.getByRole("textbox", { name: "Note" })).toHaveValue("");
	},
};

/** "Use this draft" fills the note, the member edits it, and only Record writes. */
export const DraftUsedThenEdited: Story = {
	args: { prompt: true, draftNote: DRAFT },
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Use this draft" }));
		const note = canvas.getByRole("textbox", { name: "Note" });
		await expect(note).toHaveValue(DRAFT);
		await expect(args.onRecord).not.toHaveBeenCalled();
		await userEvent.type(note, " Ask Alan to walk it.");
		await userEvent.click(canvas.getByRole("radio", { name: /^Acted on/u }));
		await userEvent.click(canvas.getByRole("button", { name: "Record" }));
		await expect(args.onRecord).toHaveBeenCalledWith({
			state: "ACTED_ON",
			note: `${DRAFT} Ask Alan to walk it.`,
		});
	},
};

/** A member wrote first, then used the draft: their own words are one press away, never lost (review of #5471). */
export const DraftAfterOwnWords: Story = {
	args: { prompt: true, draftNote: DRAFT },
	play: async ({ args, canvas }) => {
		const note = canvas.getByRole("textbox", { name: "Note" });
		await userEvent.type(note, "We disagree: the route is documented in our appendix.");
		await userEvent.click(canvas.getByRole("button", { name: "Use this draft" }));
		await expect(note).toHaveValue(DRAFT);
		await userEvent.click(canvas.getByRole("button", { name: "Restore your note" }));
		await expect(note).toHaveValue("We disagree: the route is documented in our appendix.");
		await expect(canvas.queryByRole("button", { name: "Restore your note" })).toBeNull();
		await expect(args.onRecord).not.toHaveBeenCalled();
	},
};

/** Recording names the record the member saw, so a newer one makes the server refuse instead of overwrite. */
export const RecordsAgainstTheSeenRecord: Story = {
	args: { currentState: "OPEN", currentRecordId: "d1000000-0000-4000-8000-000000000001" },
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("radio", { name: /^Acted on/u }));
		await userEvent.click(canvas.getByRole("button", { name: "Record" }));
		await expect(args.onRecord).toHaveBeenCalledWith({
			state: "ACTED_ON",
			note: "",
			supersedesId: "d1000000-0000-4000-8000-000000000001",
		});
	},
};

/** A team declines: Dismissed, with its reason in the note. */
export const Declined: Story = {
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("radio", { name: /^Dismissed/u }));
		await userEvent.type(
			canvas.getByRole("textbox", { name: "Note" }),
			"Out of scope for this course.",
		);
		await userEvent.click(canvas.getByRole("button", { name: "Record" }));
		await expect(args.onRecord).toHaveBeenCalledWith({
			state: "DISMISSED",
			note: "Out of scope for this course.",
		});
	},
};

/** Recording: the button says so at once. */
export const Recording: Story = {
	args: { pending: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Recording…" })).toBeVisible();
	},
};

/** Another member recorded first: nothing was overwritten, and the form says what to do. */
export const SupersededError: Story = {
	args: { recordError: "SUPERSEDED" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/Another member recorded a newer decision/u)).toBeVisible();
	},
};

export const FailedError: Story = {
	args: { recordError: "FAILED" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Your decision was not recorded. Try again.")).toBeVisible();
	},
};
