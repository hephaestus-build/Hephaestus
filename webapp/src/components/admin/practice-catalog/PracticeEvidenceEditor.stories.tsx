import type { Meta, StoryObj } from "@storybook/react-vite";
import { useState } from "react";
import { expect, fn, within } from "storybook/test";

import { Button } from "@/components/ui/button";
import {
	mockConversationBinding,
	mockConversationWorkType,
	mockDocumentBinding,
	mockDocumentWorkType,
	mockPullRequestBinding,
	mockPullRequestWorkType,
} from "@/mocks/fixtures/practice";
import { expectNoOverflowingElement } from "@/test/reflow";

import { PracticeEvidenceEditor } from "./PracticeEvidenceEditor";

function ControlledEvidence(args: React.ComponentProps<typeof PracticeEvidenceEditor>) {
	const [needs, setNeeds] = useState(args.needs);
	return <PracticeEvidenceEditor {...args} needs={needs} onChange={setNeeds} />;
}

const meta = {
	title: "Workspace admin/Practices/Occasion evidence",
	component: PracticeEvidenceEditor,
	args: {
		options: mockPullRequestWorkType,
		needs: mockPullRequestBinding.needs,
		idPrefix: "practice-occasion",
		onChange: fn(),
	},
	parameters: { layout: "padded" },
	tags: ["autodocs"],
} satisfies Meta<typeof PracticeEvidenceEditor>;

export default meta;
type Story = StoryObj<typeof meta>;

export const RecommendedEvidence: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Code changes")).toBeVisible();
		await expect(canvas.getByText("Nothing")).toBeVisible();
	},
};

export const SourcesAreGrouped: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Choose sources" }));

		await expect(canvas.getByText("The work itself")).toBeVisible();
		await expect(canvas.getByText("Around the work")).toBeVisible();
		await expect(canvas.getByText("This person's history")).toBeVisible();
	},
};

export const EveryChoiceIsVisible: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Choose sources" }));

		const diff = within(canvas.getByRole("radiogroup", { name: "How Code changes is used" }));
		await expect(diff.getByRole("radio", { name: "Required" })).toBeChecked();
		await expect(diff.getByRole("radio", { name: "Context" })).toBeVisible();
		await expect(diff.getByRole("radio", { name: "Off" })).toBeVisible();

		// Offered only where the contract can promise a whole capture. Linked work items never can, so
		// the control is absent rather than present-and-refused on save.
		await expect(
			canvas.getByRole("checkbox", { name: /absent from Code changes/ }),
		).not.toBeChecked();
		await expect(
			canvas.queryByRole("checkbox", { name: /absent from Linked work items/ }),
		).toBeNull();
	},
};

export const AbsenceClaimNeedsARequiredSource: Story = {
	render: (args) => <ControlledEvidence {...args} />,
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Choose sources" }));
		const comments = within(
			canvas.getByRole("radiogroup", { name: "How Inline review comments is used" }),
		);
		await expect(
			canvas.getByRole("checkbox", { name: /absent from Inline review comments/ }),
		).toBeVisible();

		await userEvent.click(comments.getByRole("radio", { name: "Context" }));

		await expect(
			canvas.queryByRole("checkbox", { name: /absent from Inline review comments/ }),
		).toBeNull();
	},
};

export const ReadsASourceExhaustively: Story = {
	args: {
		needs: [
			{ sourceKind: "scm.pull-request.core", stance: "REQUIRED" },
			{ sourceKind: "scm.repository.tree", stance: "CONTEXTUAL" },
			{ sourceKind: "scm.review-threads", stance: "EXHAUSTIVE" },
		],
	},
	play: async ({ canvas }) => {
		// "captured whole", not "nothing is missing anywhere": the claim is about this capture of this
		// source, which is the only thing completeness is ever measured against.
		await expect(canvas.getByText("· captured whole")).toBeVisible();
		await expect(canvas.getByText("Repository files and history")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Use recommended evidence" })).toBeVisible();
	},
};

/**
 * What the stance does and what it costs, both on screen: the bound one capture is taken under is
 * there before it is ticked, and the refusal it buys arrives with the tick rather than with the
 * review that was skipped.
 */
export const TheAbsenceClaimStatesItsBound: Story = {
	render: (args) => <ControlledEvidence {...args} />,
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Choose sources" }));
		const claim = canvas.getByRole("checkbox", {
			name: "May claim something is absent from Inline review comments",
		});
		await expect(claim).toHaveAccessibleDescription(/Up to the 500 most recent inline comments/);
		await expect(claim).not.toHaveAccessibleDescription(/refuses the review/);

		await userEvent.click(claim);

		await expect(claim).toHaveAccessibleDescription(/A partial capture then refuses the review/);
		await expect(claim).toHaveAccessibleDescription(/Up to the 500 most recent inline comments/);
	},
};

export const ADocumentOffersLess: Story = {
	args: { options: mockDocumentWorkType, needs: mockDocumentBinding.needs },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Choose sources" }));

		await expect(canvas.getByText("The work itself")).toBeVisible();
		await expect(canvas.queryByText("Around the work")).toBeNull();
		await expect(
			canvas.getByRole("radiogroup", { name: "How Document under review is used" }),
		).toBeVisible();
	},
};

export const AConversationReadsOneThread: Story = {
	args: { options: mockConversationWorkType, needs: mockConversationBinding.needs },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Choose sources" }));

		await expect(canvas.getByText("Must be captured whole")).toBeVisible();
	},
};

export const NothingRequiredYet: Story = {
	args: { needs: [], invalid: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Nothing yet")).toBeVisible();
		// The invalid state opens the source list, because the fix is not reachable from the summary.
		await expect(
			canvas.getByRole("radiogroup", { name: "How Code changes is used" }),
		).toBeVisible();
	},
};

/** Drives `invalid` the way a form does, including re-rendering without changing it. */
function SubmittedIntoInvalid(args: React.ComponentProps<typeof PracticeEvidenceEditor>) {
	const [invalid, setInvalid] = useState(false);
	const [renders, setRenders] = useState(0);
	return (
		<div className="space-y-3">
			<Button type="button" onClick={() => setInvalid(true)}>
				Submit the form
			</Button>
			<Button type="button" variant="outline" onClick={() => setRenders(renders + 1)}>
				Type something else
			</Button>
			<PracticeEvidenceEditor {...args} invalid={invalid} />
		</div>
	);
}

/**
 * `invalid` stays true while the author fixes it, so an editor keyed on the flag rather than on the
 * transition into it would re-open under the caret on every keystroke.
 */
export const SubmittingRevealsTheSources: Story = {
	args: { needs: [] },
	render: (args) => <SubmittedIntoInvalid {...args} />,
	play: async ({ canvas, userEvent }) => {
		const sources = { name: "How Code changes is used" };
		await expect(canvas.queryByRole("radiogroup", sources)).toBeNull();

		await userEvent.click(canvas.getByRole("button", { name: "Submit the form" }));
		await expect(canvas.getByRole("radiogroup", sources)).toBeVisible();

		// Closed again by the author, and it stays closed while the error stands.
		await userEvent.click(canvas.getByRole("button", { name: "Choose sources" }));
		await userEvent.click(canvas.getByRole("button", { name: "Type something else" }));
		await expect(canvas.queryByRole("radiogroup", sources)).toBeNull();
	},
};

export const Disabled: Story = {
	args: { disabled: true },
};

export const NarrowViewport: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvasElement }) => {
		await expectNoOverflowingElement(canvasElement);
	},
};
