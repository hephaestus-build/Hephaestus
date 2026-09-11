import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

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

export const Default: Story = {};

export const ValidationErrors: Story = {
	play: async ({ args }) => {
		await userEvent.click(await screen.findByRole("button", { name: "Publish survey" }));
		// Title, purpose and the one empty question: three problems, each linked to its field.
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
		await userEvent.click(await screen.findByRole("combobox", { name: "Answer type" }));
		await userEvent.click(await screen.findByRole("option", { name: "Single choice" }));
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
			const fields = screen.getAllByRole("textbox", { name: "Prompt" });
			for (const [index, value] of values.entries()) await expect(fields[index]).toHaveValue(value);
		};
		const [first, second] = screen.getAllByRole("textbox", { name: "Prompt" });
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

export const Preview: Story = {
	play: async () => {
		await userEvent.type(
			await screen.findByRole("textbox", { name: "Title" }),
			"Onboarding check-in",
		);
		await userEvent.type(
			screen.getByRole("textbox", { name: "Prompt" }),
			"What slowed you down in your first week?",
		);
		await userEvent.click(screen.getByRole("tab", { name: "Preview" }));
		await expectSettledVisible(await screen.findByRole("heading", { name: "Onboarding check-in" }));
		await expect(screen.getByText("1 question · under a minute")).toBeVisible();
		await expect(screen.getByText(/What slowed you down in your first week\?/)).toBeVisible();
	},
};

export const Publishing: Story = {
	args: { isPending: true },
	play: async () => {
		await expect(await screen.findByRole("button", { name: "Publishing…" })).toBeDisabled();
		await expect(screen.getByRole("textbox", { name: "Title" })).toBeDisabled();
	},
};
