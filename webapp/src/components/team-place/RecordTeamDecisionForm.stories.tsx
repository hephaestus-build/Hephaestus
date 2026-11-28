import type { Meta, StoryObj } from "@storybook/react-vite";
import { useState } from "react";
import { expect, fn, userEvent } from "storybook/test";

import { Button } from "@/components/ui/button";

import { RecordTeamDecisionForm, type RecordTeamDecisionFormProps } from "./RecordTeamDecisionForm";

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

const OWN = "We disagree: the route is documented in our appendix.";

/**
 * The order table for "Use this draft" (review of #5471): whatever the order of typing, drafting and editing, the
 * member's own words stay. Only their typing removes a character.
 */
export const OwnWordsThenDraft: Story = {
	args: { prompt: true, draftNote: DRAFT },
	play: async ({ args, canvas }) => {
		const note = canvas.getByRole("textbox", { name: "Note" });
		await userEvent.type(note, OWN);
		await userEvent.click(canvas.getByRole("button", { name: "Use this draft" }));
		await expect(note).toHaveValue(`${OWN}\n\n${DRAFT}`);
		await expect(args.onRecord).not.toHaveBeenCalled();
	},
};

/** The draft, edited, then the draft again: the edited text stays, and the draft is added below it. */
export const DraftEditedThenDraftAgain: Story = {
	args: { prompt: true, draftNote: DRAFT },
	play: async ({ canvas }) => {
		const note = canvas.getByRole("textbox", { name: "Note" });
		await userEvent.click(canvas.getByRole("button", { name: "Use this draft" }));
		await userEvent.type(note, " Ask Alan to walk it.");
		await userEvent.click(canvas.getByRole("button", { name: "Use this draft" }));
		await expect(note).toHaveValue(`${DRAFT} Ask Alan to walk it.\n\n${DRAFT}`);
	},
};

/** Own words, the draft twice: the member's words still lead the note. */
export const OwnWordsThenDraftTwice: Story = {
	args: { prompt: true, draftNote: DRAFT },
	play: async ({ canvas }) => {
		const note = canvas.getByRole("textbox", { name: "Note" });
		await userEvent.type(note, OWN);
		await userEvent.click(canvas.getByRole("button", { name: "Use this draft" }));
		await userEvent.click(canvas.getByRole("button", { name: "Use this draft" }));
		await expect(note).toHaveValue(`${OWN}\n\n${DRAFT}\n\n${DRAFT}`);
	},
};

/** The form, with a control that changes the current record after it opened, as a refetch would. */
function NewerRecordArrives(args: RecordTeamDecisionFormProps) {
	const [seen, setSeen] = useState(args.currentRecordId);
	return (
		<>
			<Button
				size="sm"
				variant="outline"
				onClick={() => setSeen("d1000000-0000-4000-8000-000000000099")}
			>
				A newer record arrives
			</Button>
			<RecordTeamDecisionForm {...args} currentRecordId={seen} />
		</>
	);
}

/** The record the member saw is fixed when the form opens; a record that arrives later is not taken over silently. */
export const SupersedesTheRecordSeenAtOpening: Story = {
	args: { currentRecordId: "d1000000-0000-4000-8000-000000000001" },
	render: (args) => <NewerRecordArrives {...args} />,
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "A newer record arrives" }));
		await userEvent.click(canvas.getByRole("button", { name: "Record" }));
		await expect(args.onRecord).toHaveBeenCalledWith({
			state: "OPEN",
			note: "",
			supersedesId: "d1000000-0000-4000-8000-000000000001",
		});
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
