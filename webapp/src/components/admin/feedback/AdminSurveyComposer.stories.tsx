import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";

import { DetailDrawerStack } from "@/components/core/detail-drawer/DetailDrawerStack";
import { LevelCancel } from "@/components/core/detail-drawer/LevelCancel";
import { withPageBehind } from "@/stories/decorators";
import { Stateful } from "@/stories/stateful";
import { expectGenuinelyDisabled } from "@/test/controls";
import { expectSettledVisible } from "@/test/overlay";

import { GUARDED_SURVEY_LEVEL_KINDS, surveyLevel } from "./admin-surveys-search";
import { AdminSurveyComposer } from "./AdminSurveyComposer";

const meta = {
	title: "Instance admin/Product feedback/Survey composer",
	component: AdminSurveyComposer,
	parameters: { layout: "fullscreen", chromatic: { viewports: [1440] } },
	decorators: [withPageBehind],
	args: {
		workspaces: [
			{ id: 7, displayName: "Acme" },
			{ id: 9, displayName: "Globex" },
		],
		isPending: false,
		cancel: <LevelCancel />,
		onSubmit: fn(),
	},
	argTypes: { cancel: { control: false } },
	render: (args) => (
		<Stateful initial={[surveyLevel()]}>
			{(stack, setStack) => (
				<DetailDrawerStack
					stack={stack}
					guardedKinds={GUARDED_SURVEY_LEVEL_KINDS}
					onClose={(depth) => setStack(stack.slice(0, depth))}
				>
					{(_entry, level) => <AdminSurveyComposer {...args} nested={level.nested} />}
				</DetailDrawerStack>
			)}
		</Stateful>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof AdminSurveyComposer>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Where no study runs, the purpose is not a question: every survey is a product survey. */
export const Default: Story = {
	play: async () => {
		await screen.findByRole("textbox", { name: "Title" });
		await expect(screen.queryByRole("radiogroup", { name: "Purpose" })).toBeNull();
	},
};

export const ValidationErrors: Story = {
	play: async ({ args }) => {
		await userEvent.click(await screen.findByRole("button", { name: "Publish survey" }));
		// Title, introduction and the one empty question: three problems, each linked to its field.
		await expectSettledVisible(
			await screen.findByRole("heading", { name: "There are 3 problems" }),
		);
		await expect(screen.getByText("Give the survey a title.", { selector: "a" })).toBeVisible();
		await expect(screen.getByText("Question 1: Write the question.")).toBeVisible();
		// `aria-invalid` says that the field is wrong; `aria-describedby` says why (WCAG 3.3.1).
		const title = screen.getByRole("textbox", { name: "Title" });
		await expect(title).toHaveAttribute("aria-invalid", "true");
		const describedBy = title.getAttribute("aria-describedby") ?? "";
		await expect(document.getElementById(describedBy)).toHaveTextContent(
			"Give the survey a title.",
		);
		await expect(args.onSubmit).not.toHaveBeenCalled();
	},
};

export const ChoiceQuestion: Story = {
	play: async () => {
		await expect(screen.queryByRole("checkbox", { name: "Allow “Something else”" })).toBeNull();
		await userEvent.click(await screen.findByRole("combobox", { name: "Answer type" }));
		await userEvent.click(await screen.findByRole("option", { name: "Single choice" }));
		const allowOther = await screen.findByRole("checkbox", { name: "Allow “Something else”" });
		await expect(allowOther).not.toBeChecked();
		await expect(allowOther).toHaveAccessibleDescription(
			"Adds a line under the choices for an answer you did not list.",
		);
		await userEvent.click(allowOther);
		await expect(allowOther).toBeChecked();
		const choices = await screen.findByRole("textbox", { name: "Choices (one per line)" });
		await userEvent.type(choices, "Yes{enter}Yes");
		await userEvent.click(screen.getByRole("button", { name: "Publish survey" }));
		await expect(
			await screen.findByText("Question 1: Each choice must be different."),
		).toBeVisible();
	},
};

export const RatingQuestion: Story = {
	play: async () => {
		await userEvent.click(await screen.findByRole("combobox", { name: "Answer type" }));
		await userEvent.click(await screen.findByRole("option", { name: "Rating (1–5)" }));
		await expect(await screen.findByRole("textbox", { name: "Label for 1" })).toBeVisible();
		await expect(screen.getByRole("textbox", { name: "Label for 5" })).toBeVisible();
	},
};

export const ManyQuestions: Story = {
	play: async () => {
		const add = await screen.findByRole("button", { name: "Add question" });
		for (let count = 1; count < 6; count += 1) await userEvent.click(add);
		await expect(screen.getAllByRole("group", { name: /^Question \d+$/ })).toHaveLength(6);
		await expect(
			screen.getByText("This survey has 6 questions; response rates drop sharply beyond 5."),
		).toBeVisible();
		await expectGenuinelyDisabled(screen.getByRole("button", { name: "Move question 1 up" }));
		await expectGenuinelyDisabled(screen.getByRole("button", { name: "Move question 6 down" }));

		// Moving swaps the questions themselves, prompts included, not just their numbers.
		const expectPrompts = async (...values: string[]) => {
			const fields = screen.getAllByRole("textbox", { name: "Question" });
			for (const [index, value] of values.entries()) await expect(fields[index]).toHaveValue(value);
		};
		const [first, second] = screen.getAllByRole("textbox", { name: "Question" });
		if (!first || !second) throw new Error("expected two prompts");
		await userEvent.type(first, "First");
		await userEvent.type(second, "Second");
		await userEvent.click(screen.getByRole("button", { name: "Move question 2 up" }));
		await expectPrompts("Second", "First");
		await userEvent.click(screen.getByRole("button", { name: "Move question 1 down" }));
		await expectPrompts("First", "Second");

		await userEvent.click(screen.getByRole("button", { name: "Remove question 6" }));
		await expect(screen.getAllByRole("group", { name: /^Question \d+$/ })).toHaveLength(5);
	},
};

/** The fields a preview needs; the schedule and the questions are the story's own. */
async function fillSurvey(title: string) {
	await userEvent.type(await screen.findByRole("textbox", { name: "Title" }), title);
	await userEvent.type(
		screen.getByRole("textbox", { name: "Introduction" }),
		"To decide what the next release should focus on.",
	);
}

/** Preview opens the dialog members get, on the draft as it stands, and sends nothing. */
export const Preview: Story = {
	play: async () => {
		await fillSurvey("Onboarding check-in");
		await userEvent.type(
			screen.getByRole("textbox", { name: "Question" }),
			"What slowed you down in your first week?",
		);
		await userEvent.type(screen.getByLabelText("End"), "2099-01-01T09:00");
		await userEvent.click(screen.getByRole("button", { name: "Preview" }));
		const dialog = within(await screen.findByRole("dialog", { name: "Onboarding check-in" }));
		await expectSettledVisible(dialog.getByText(/1 question · under a minute · closes in/));
		await expect(dialog.getByRole("progressbar")).toHaveTextContent("Question 1 of 1");
		await expect(dialog.getByText(/What slowed you down in your first week\?/)).toBeVisible();
		await userEvent.keyboard("{Escape}");
		await waitFor(() =>
			expect(screen.queryByRole("dialog", { name: "Onboarding check-in" })).toBeNull(),
		);
		// Closing the preview is not leaving the composer, so the unsaved-changes guard stays down.
		await expect(screen.queryByRole("alertdialog")).toBeNull();
		await expect(screen.getByRole("dialog", { name: "Create survey" })).toBeVisible();
		await expect(screen.getByRole("button", { name: "Preview" })).toHaveFocus();
	},
};

/** Preview refuses what Publish would refuse, and explains it in the same place. */
export const PreviewRefused: Story = {
	play: async ({ args }) => {
		await userEvent.type(await screen.findByRole("textbox", { name: "Title" }), "Check-in");
		await userEvent.click(screen.getByRole("button", { name: "Preview" }));
		await expectSettledVisible(
			await screen.findByRole("heading", { name: "There are 2 problems" }),
		);
		await expect(screen.queryByRole("dialog", { name: "Check-in" })).toBeNull();
		await expect(args.onSubmit).not.toHaveBeenCalled();
	},
};

export const PreviewWithAnotherAnswer: Story = {
	play: async () => {
		await fillSurvey("Where do you read feedback?");
		await userEvent.type(screen.getByRole("textbox", { name: "Question" }), "Where, mostly?");
		await userEvent.click(screen.getByRole("combobox", { name: "Answer type" }));
		await userEvent.click(await screen.findByRole("option", { name: "Single choice" }));
		await userEvent.type(
			await screen.findByRole("textbox", { name: "Choices (one per line)" }),
			"On the pull request{enter}On my practice page",
		);
		await userEvent.click(screen.getByRole("checkbox", { name: "Allow “Something else”" }));
		await userEvent.click(screen.getByRole("button", { name: "Preview" }));
		const dialog = within(
			await screen.findByRole("dialog", { name: "Where do you read feedback?" }),
		);
		await expectSettledVisible(dialog.getByText("On the pull request"));
		await expect(dialog.getByRole("radio", { name: "On my practice page" })).not.toBeChecked();
		await expect(dialog.getByRole("textbox", { name: "Something else" })).toBeVisible();
	},
};

/**
 * Only an instance that names a research organisation can publish a research survey, so the
 * purpose is offered there and nowhere else; the preview then carries the research framing.
 */
export const WithResearchProgramme: Story = {
	args: { researchOrganization: "Technical University of Munich" },
	parameters: { chromatic: { viewports: [320, 1440] } },
	play: async () => {
		await fillSurvey("Acting on feedback");
		await userEvent.type(
			screen.getByRole("textbox", { name: "Question" }),
			"What did you do next?",
		);
		await expect(screen.getByRole("radio", { name: "Product" })).toBeChecked();
		await userEvent.click(screen.getByRole("radio", { name: "Research" }));
		await userEvent.click(screen.getByRole("button", { name: "Preview" }));
		const dialog = within(await screen.findByRole("dialog", { name: "Acting on feedback" }));
		await expectSettledVisible(dialog.getByText("Research"));
		await expect(
			dialog.getByText(/study run by Technical University of Munich, which you agreed to join/),
		).toBeVisible();
		await expect(dialog.getByRole("link", { name: "User settings" })).toBeVisible();
	},
};

export const Publishing: Story = {
	args: { isPending: true },
	play: async () => {
		await expect(await screen.findByRole("button", { name: "Publishing…" })).toBeDisabled();
		await expect(screen.getByRole("textbox", { name: "Title" })).toBeDisabled();
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectSettledVisible(await screen.findByRole("textbox", { name: "Title" }));
		await expect(screen.getByRole("button", { name: "Publish survey" })).toBeVisible();
	},
};
