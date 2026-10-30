import type { Decorator, Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, waitFor, within } from "storybook/test";

import { Button } from "@/components/ui/button";
import { expectNoOverflowingElement } from "@/stories/reflow";

import { mockDefinitionOptions, mockScan, practiceValueOf } from "./fixtures";
import {
	PracticeDefinitionForm,
	type PracticeDefinitionSubmission,
} from "./PracticeDefinitionForm";

const groups = [
	{ slug: "review-ready-work", name: "Review-ready work" },
	{ slug: "code-quality", name: "Code quality" },
];

type Submit = (value: PracticeDefinitionSubmission) => void | Promise<void>;

const submit = fn<Submit>();

function lastSaved(): PracticeDefinitionSubmission | undefined {
	return submit.mock.lastCall?.[0];
}

const fullHeight: Decorator = (Story) => (
	<div className="flex h-dvh flex-col">
		<Story />
	</div>
);

const meta = {
	component: PracticeDefinitionForm,
	args: {
		groups,
		definitionOptions: mockDefinitionOptions,
		isPending: false,
		cancelAction: (
			<Button type="button" variant="outline">
				Cancel
			</Button>
		),
		onSubmit: submit,
	},
	argTypes: { cancelAction: { control: false } },
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
	decorators: [fullHeight],
} satisfies Meta<typeof PracticeDefinitionForm>;

export default meta;
type Story = StoryObj<typeof meta>;

function editing(initialData = practiceValueOf()) {
	return { mode: "edit" as const, initialData };
}

export const Create: Story = {
	args: { mode: "create" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("radio", { name: /^Pull request\b(?! review)/u })).toBeChecked();
		const strip = within(canvas.getByRole("group", { name: "Review on" }));
		await expect(strip.getByRole("checkbox", { name: /^Opened/u })).toBeChecked();
		await expect(strip.getByRole("checkbox", { name: /^Merged/u })).not.toBeChecked();
		await expect(canvas.getByRole("radio", { name: /^Hephaestus/u })).toBeChecked();
	},
};

export const Edit: Story = {
	args: editing(),
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("radio", { name: /^Issue/u })).toBeNull();
		await expect(canvas.getByRole("checkbox", { name: "Ready for review" })).toBeChecked();
		await expect(canvas.getByRole("combobox", { name: /^About/u })).toHaveTextContent("Author");
		await expect(canvas.getByRole("button", { name: "Add condition" })).toBeVisible();
		await expect(canvas.queryByRole("textbox", { name: "File paths 1" })).toBeNull();
	},
};

export const AboutWhoeverMergedIt: Story = {
	args: editing(),
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: /^About/u }));
		const listbox = await screen.findByRole("listbox", { name: "About" });
		await expect(within(listbox).getByRole("option", { name: "The work" })).toBeVisible();
		await userEvent.click(within(listbox).getByRole("option", { name: "Whoever merged it" }));
		await expect(canvas.getByRole("combobox", { name: /^About/u })).toHaveTextContent(
			"Whoever merged it",
		);
	},
};

export const ReviewedByPeople: Story = {
	args: editing(
		practiceValueOf({
			reads: { contract: "3.0.0", sources: [] },
			reviewedBy: {
				by: "PEOPLE",
				humanReason: {
					code: "DECIDED_IN_A_MEETING",
					description: "The trade-off was agreed in a meeting no review can read.",
				},
				limits: [],
			},
		}),
	),
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("radio", { name: /^People/u })).toBeChecked();
		await expect(canvas.getByRole("textbox", { name: /Why do people review it/u })).toHaveValue(
			"The trade-off was agreed in a meeting no review can read.",
		);
		await expect(canvas.queryByRole("group", { name: "Reads" })).toBeNull();
		await expect(canvas.queryByRole("button", { name: /^Script/u })).toBeNull();
	},
};

export const ReviewedByNobody: Story = {
	args: editing(practiceValueOf({ script: "export default 1;", scan: mockScan })),
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("radio", { name: /^Nobody/u }));
		await expect(
			canvas.getByText(
				"Saving with Nobody clears the read sources, the practice script and the scan settings. Choose Hephaestus again before you save to keep them.",
			),
		).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Save changes" }));
		await waitFor(async () => expect(submit).toHaveBeenCalledOnce());
		const definition = lastSaved()?.definition;
		await expect(definition?.reviewedBy).toEqual({ by: "NOBODY", humanReason: null, limits: [] });
		await expect(definition?.script).toBeNull();
		await expect(definition?.scan).toBeNull();
	},
};

export const BuiltInScript: Story = {
	args: editing(),
	play: async ({ canvas }) => {
		const section = canvas.getByRole("button", { name: /^Script/u });
		await expect(section).toHaveAttribute("aria-expanded", "false");
		await expect(section).toHaveTextContent(/Built-in/u);
	},
};

export const CustomScript: Story = {
	args: editing(
		practiceValueOf({
			script: "export default definePracticeScript({ async run(ctx) { return ctx.review({}); } });",
		}),
	),
	play: async ({ canvas }) => {
		const section = canvas.getByRole("button", { name: /^Script/u });
		await expect(section).toHaveAttribute("aria-expanded", "true");
		await expect(section).toHaveTextContent(/The practice script that reviews this practice/u);
	},
};

// Complete permits absence claims; an unavailable optional source permits no claim about the work.
export const ReadsCompleteVersusRequired: Story = {
	args: editing(
		practiceValueOf({
			reads: {
				contract: "3.0.0",
				sources: [
					{ source: "scm.pull-request.core", need: "REQUIRED" },
					{ source: "scm.review-threads", need: "COMPLETE" },
					{ source: "scm.repository.tree", need: "OPTIONAL" },
				],
			},
		}),
	),
	play: async ({ canvas }) => {
		const reads = within(canvas.getByRole("group", { name: "Reads" }));
		await expect(reads.getByText("· captured whole")).toBeVisible();
		await expect(reads.getByText("Repository files and history")).toBeVisible();
	},
};

export const GoingWell: Story = {
	args: editing(),
	play: async ({ canvas, userEvent }) => {
		const field = canvas.getByRole("textbox", { name: "Going well" });
		await expect(field).toHaveValue("You keep each change small enough to review in one sitting.");
		await userEvent.clear(field);
		await userEvent.type(field, "You keep every change to one purpose.");
		await userEvent.click(canvas.getByRole("button", { name: "Save changes" }));
		await waitFor(async () => expect(submit).toHaveBeenCalledOnce());
		await expect(lastSaved()?.definition.guidance.goingWell).toBe(
			"You keep every change to one purpose.",
		);
	},
};

export const SavedScan: Story = {
	args: editing(practiceValueOf({ scan: mockScan })),
	play: async ({ canvas, userEvent }) => {
		await expect(
			canvas.queryByRole("switch", { name: "Include in scans" }),
		).not.toBeInTheDocument();
		const name = canvas.getByRole("textbox", { name: /^Name/u });
		await userEvent.clear(name);
		await userEvent.type(name, "Explain every change");
		await userEvent.click(canvas.getByRole("button", { name: "Save changes" }));
		await waitFor(async () => expect(submit).toHaveBeenCalledOnce());
		await expect(lastSaved()?.definition.scan).toEqual(mockScan);
	},
};

export const SavePending: Story = {
	args: { ...editing(), isPending: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Saving…" })).toBeDisabled();
		await expect(canvas.getByRole("textbox", { name: /^Name/u })).toBeDisabled();
	},
};

export const SaveFailure: Story = {
	args: {
		...editing(),
		onSubmit: fn<Submit>(async () => {
			throw new Error("Conflict");
		}),
	},
	play: async ({ canvas, userEvent }) => {
		const name = canvas.getByRole("textbox", { name: /^Name/u });
		await userEvent.clear(name);
		await userEvent.type(name, "Small, reviewable changes");
		await userEvent.click(canvas.getByRole("button", { name: "Save changes" }));
		await expect(name).toHaveValue("Small, reviewable changes");
		await expect(canvas.getByRole("button", { name: "Save changes" })).toBeEnabled();
	},
};

export const Refused: Story = {
	args: { mode: "create" },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Create practice" }));
		const summary = canvas
			.getByRole("heading", { name: /^There are \d+ problems$/u })
			.closest<HTMLElement>("[role=alert]");
		if (summary === null) {
			throw new Error("The error summary must be announced as an alert.");
		}
		await expect(
			within(summary)
				.getAllByRole("link")
				.map((link) => link.textContent),
		).toEqual([
			"Enter a name of 3 to 128 characters.",
			"Enter what to look for.",
			"Set at least one source to Required. A review cannot run without one.",
			"Enter an identifier of 3–64 lowercase letters, numbers and single hyphens.",
		]);
		await expect(submit).not.toHaveBeenCalled();
	},
};

export const NarrowViewport: Story = {
	args: { mode: "create" },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvasElement }) => {
		await expectNoOverflowingElement(canvasElement);
	},
};
