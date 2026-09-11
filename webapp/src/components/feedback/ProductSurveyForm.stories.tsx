import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, within } from "storybook/test";

import { Stateful } from "@/stories/stateful";

import { surveyQuestions } from "./product-survey-fixtures";
import { ProductSurveyForm } from "./ProductSurveyForm";

const meta = {
	title: "Product feedback/Survey form",
	component: ProductSurveyForm,
	args: { questions: surveyQuestions, draft: {}, onDraftChange: fn() },
	render: (args) => (
		<Stateful initial={args.draft}>
			{(draft, setDraft) => (
				<ProductSurveyForm
					{...args}
					draft={draft}
					onDraftChange={(next) => {
						args.onDraftChange(next);
						setDraft(next);
					}}
				/>
			)}
		</Stateful>
	),
	decorators: [(Story) => <div className="max-w-lg">{<Story />}</div>],
	tags: ["autodocs"],
} satisfies Meta<typeof ProductSurveyForm>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Answered: Story = {
	args: {
		draft: {
			useful: 4,
			channel: "On the pull request",
			recommend: 9,
			improve: "Shorter feedback.",
		},
	},
};

export const Disabled: Story = { args: { disabled: true, draft: { useful: 4 } } };

/** An optional closed question can be taken back, whether it is one choice or several. */
export const ClearsOptionalChoices: Story = {
	args: {
		questions: [
			{
				id: "channel",
				prompt: "Where do you read feedback most often?",
				type: "SINGLE_CHOICE",
				options: ["On the pull request", "On my practice page"],
				required: false,
			},
			{
				id: "channels",
				prompt: "Where else do you read it?",
				type: "MULTIPLE_CHOICE",
				options: ["On the pull request", "On my practice page", "In conversation with Heph"],
				required: false,
			},
		],
	},
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("radio", { name: "On my practice page" }));
		await expect(canvas.getByRole("radio", { name: "On my practice page" })).toBeChecked();
		await userEvent.click(canvas.getByRole("checkbox", { name: "On the pull request" }));
		await userEvent.click(canvas.getByRole("checkbox", { name: "In conversation with Heph" }));
		await expect(args.onDraftChange).toHaveBeenLastCalledWith({
			channel: "On my practice page",
			channels: ["On the pull request", "In conversation with Heph"],
		});
		const [clearChoice, clearChoices] = canvas.getAllByRole("button", { name: "Clear answer" });
		if (!clearChoice || !clearChoices) throw new Error("both optional questions offer a clear");
		await userEvent.click(clearChoices);
		await expect(canvas.getByRole("checkbox", { name: "On the pull request" })).not.toBeChecked();
		await userEvent.click(clearChoice);
		await expect(canvas.getByRole("radio", { name: "On my practice page" })).not.toBeChecked();
		await expect(canvas.queryByRole("button", { name: "Clear answer" })).toBeNull();
	},
};

/** A scale is a radio group: arrow keys move the choice, and the end labels describe it. */
export const AnswersAScaleByKeyboard: Story = {
	play: async ({ canvas, args }) => {
		const scale = canvas.getByRole("radiogroup", {
			name: /How useful is the practice feedback/,
		});
		await expect(scale).toHaveAttribute("aria-required", "true");
		await expect(scale).toHaveAccessibleDescription(/1 = Not useful.*5 = Very useful/);
		await userEvent.click(within(scale).getByRole("radio", { name: "3" }));
		await userEvent.keyboard("{ArrowRight}");
		await expect(within(scale).getByRole("radio", { name: "4" })).toBeChecked();
		await expect(args.onDraftChange).toHaveBeenLastCalledWith({ useful: 4 });
	},
};
