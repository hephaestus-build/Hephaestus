import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";
import { UnsavedPracticeDraft } from "@/components/admin/practice-editor/unsaved-practice-draft";

import { bundledGuidance, unthemedVisual } from "@/stories/practice-guidance-story-mock-data";
import { expectTablesScrollInPlace } from "@/stories/reflow";

import { releaseBase as base, releaseProposal } from "./fixtures";
import { PracticeReleaseReview } from "./PracticeReleaseReview";

const proposal = releaseProposal({
	fields: [
		{ field: "CRITERIA", changedLocally: true, offeredChanged: true, conflict: true },
		{ field: "NAME", changedLocally: true, offeredChanged: false, conflict: false },
		{ field: "LINE_NOTES", changedLocally: false, offeredChanged: true, conflict: false },
	],
});

const meta = {
	component: PracticeReleaseReview,
	args: {
		proposal,
		pending: null,
		declineNote:
			"Declining keeps your practice as it is until the catalog changes again. Reset to catalog stays available on the practice.",
		onAccept: fn(),
		onDecline: fn(),
	},
	parameters: { layout: "padded" },
	tags: ["autodocs"],
} satisfies Meta<typeof PracticeReleaseReview>;

export default meta;
type Story = StoryObj<typeof meta>;

export const ConflictingUpdate: Story = {
	play: async ({ args }) => {
		const accept = screen.getByRole("button", { name: "Accept selected fields" });
		await expect(accept).toBeDisabled();
		await expect(screen.getByText("Local edit")).toBeVisible();
		await userEvent.click(
			within(
				screen.getByRole("radiogroup", { name: "Use a version for Review criteria" }),
			).getByRole("radio", { name: "Proposed" }),
		);
		await expect(accept).toBeEnabled();
		await userEvent.click(accept);
		await expect(args.onAccept).toHaveBeenCalledWith({
			LINE_NOTES: "OFFERED",
			CRITERIA: "OFFERED",
		});
	},
};

export const LineNotesInWords: Story = {
	play: async () => {
		await expect(screen.getByText("Summary only")).toBeVisible();
		await expect(screen.getAllByText("Leaves line notes").length).toBeGreaterThan(0);
		await expect(screen.queryByText(/lineNotes/u)).not.toBeInTheDocument();
	},
};

export const AdoptedVersionUnknown: Story = {
	args: { proposal: { ...proposal, base: null } },
	play: async ({ args }) => {
		await expect(screen.getByText(/Choose a version for each changed field/u)).toBeVisible();
		await expect(screen.getAllByText("Not recorded").length).toBeGreaterThan(0);
		const accept = screen.getByRole("button", { name: "Accept selected fields" });
		const lineNotes = within(
			screen.getByRole("radiogroup", { name: "Use a version for Line notes" }),
		);
		await expect(lineNotes.getByRole("radio", { name: "Proposed" })).not.toBeChecked();
		await expect(lineNotes.getByRole("radio", { name: "Current" })).not.toBeChecked();
		await userEvent.click(
			within(
				screen.getByRole("radiogroup", { name: "Use a version for Review criteria" }),
			).getByRole("radio", { name: "Proposed" }),
		);
		await expect(accept).toBeDisabled();
		await userEvent.click(lineNotes.getByRole("radio", { name: "Current" }));
		await expect(accept).toBeEnabled();
		await userEvent.click(accept);
		await expect(args.onAccept).toHaveBeenCalledWith({
			CRITERIA: "OFFERED",
			LINE_NOTES: "CURRENT",
		});
	},
};

const readyProposal = {
	...proposal,
	current: base,
	fields: proposal.fields
		.filter((field) => field.offeredChanged)
		.map((field) => ({ ...field, changedLocally: false, conflict: false })),
};

export const Accepting: Story = {
	args: { proposal: readyProposal, pending: "accept" },
	play: async () => {
		await expect(
			screen.getByRole("button", { name: "Accepting selected fields…" }),
		).toHaveAttribute("aria-disabled", "true");
		await expect(screen.getByRole("button", { name: "Decline this update" })).toBeDisabled();
		for (const choice of screen.getAllByRole("radio")) {
			await expect(choice).toHaveAttribute("aria-disabled", "true");
		}
	},
};

/**
 * Accepting reloads the practice form around the review, so an unsaved draft there is discarded only
 * after the reader confirms. Without a draft, Accept acts at once.
 */
export const UnsavedDraft: Story = {
	args: { proposal: readyProposal },
	decorators: [
		(Story) => (
			<UnsavedPracticeDraft value>
				<Story />
			</UnsavedPracticeDraft>
		),
	],
	play: async ({ args }) => {
		await userEvent.click(screen.getByRole("button", { name: "Accept selected fields" }));
		const confirmation = await screen.findByRole("alertdialog", {
			name: "Accept selected fields?",
		});
		await expect(args.onAccept).not.toHaveBeenCalled();
		await userEvent.click(
			within(confirmation).getByRole("button", { name: "Accept selected fields" }),
		);
		await expect(args.onAccept).toHaveBeenCalledWith({
			CRITERIA: "OFFERED",
			LINE_NOTES: "OFFERED",
		});
	},
};

export const Declining: Story = {
	args: { proposal: readyProposal, pending: "decline" },
	play: async () => {
		await expect(screen.getByRole("button", { name: "Declining update…" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
		await expect(screen.getByRole("button", { name: "Accept selected fields" })).toBeDisabled();
		await expect(screen.getByText(/Reset to catalog stays available/u)).toBeVisible();
		for (const choice of screen.getAllByRole("radio")) {
			await expect(choice).toHaveAttribute("aria-disabled", "true");
		}
	},
};

export const NarrowComparison: Story = {
	args: {
		proposal: {
			...proposal,
			fields: [{ field: "CRITERIA", changedLocally: false, offeredChanged: true, conflict: false }],
		},
	},
	parameters: { chromatic: { viewports: [320, 930] } },
	render: (args) => (
		<section aria-label="Practice update panel" className="grid w-full max-w-160">
			<PracticeReleaseReview {...args} />
		</section>
	),
	play: async ({ canvas }) => {
		const panel = canvas.getByRole("region", { name: "Practice update panel" });
		await expect(panel.scrollWidth).toBeLessThanOrEqual(panel.clientWidth + 1);
		await expectTablesScrollInPlace(panel, { expectOverflow: true });
		const accept = canvas.getByRole("button", { name: "Accept selected fields" });
		await expect(accept).toBeEnabled();
		const bounds = accept.getBoundingClientRect();
		const panelBounds = panel.getBoundingClientRect();
		await expect(bounds.left).toBeGreaterThanOrEqual(panelBounds.left);
		await expect(bounds.right).toBeLessThanOrEqual(panelBounds.right);
		await expect(
			document.elementFromPoint(bounds.left + bounds.width / 2, bounds.top + bounds.height / 2),
		).toBe(accept);
	},
};

/**
 * A visual or guide reads as the developer would see it, not as markup: each version's picture with
 * its description, and each guide's text with its figures.
 */
export const GuidanceUpdate: Story = {
	args: {
		proposal: {
			...proposal,
			current: { ...base, guidance: { ...base.guidance, visual: unthemedVisual } },
			offered: {
				...base,
				guidance: {
					...base.guidance,
					visual: bundledGuidance.visual,
					guide: bundledGuidance.guide,
				},
			},
			fields: [{ field: "GUIDANCE", changedLocally: true, offeredChanged: true, conflict: true }],
		},
	},
	play: async ({ args }) => {
		await expect(screen.getByRole("img", { name: unthemedVisual.alt })).toBeVisible();
		await expect(screen.getByRole("img", { name: bundledGuidance.visual.alt })).toBeVisible();
		// A picture's description wraps inside its column instead of running into the next one.
		const description = screen.getByText(bundledGuidance.visual.alt, { selector: "p" });
		await expect(description.scrollWidth).toBeLessThanOrEqual(description.clientWidth);
		await expect(screen.getByText(/^## How to do it/u)).toBeVisible();
		await expect(screen.queryByText(/<svg/u)).not.toBeInTheDocument();

		await userEvent.click(
			within(screen.getByRole("radiogroup", { name: "Use a version for Guidance" })).getByRole(
				"radio",
				{ name: "Current" },
			),
		);
		await userEvent.click(screen.getByRole("button", { name: "Accept selected fields" }));
		await expect(args.onAccept).toHaveBeenCalledWith({ GUIDANCE: "CURRENT" });
	},
};

export const PracticeScriptUpdate: Story = {
	args: {
		proposal: {
			...proposal,
			current: base,
			offered: { ...base, script: "export default { models: {} };" },
			fields: [{ field: "SCRIPT", changedLocally: false, offeredChanged: true, conflict: false }],
		},
	},
	play: async () => {
		await expect(screen.getByRole("rowheader", { name: /Practice script/u })).toBeVisible();
		await expect(screen.getAllByText("Built-in").length).toBe(2);
	},
};
