import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, within } from "storybook/test";

import { Stateful } from "@/stories/stateful";

import { surveyQuestions } from "./product-survey-fixtures";
import { ProductSurveyForm } from "./ProductSurveyForm";

const meta = {
	title: "Surveys/Product survey form",
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

/** Rating, single choice, recommendation and free text, in the order a short survey should ask them. */
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

export const MultipleChoice: Story = {
	args: {
		questions: [
			{
				id: "channels",
				prompt: "Where do you read feedback?",
				type: "MULTIPLE_CHOICE",
				options: ["On the pull request", "On my practice page", "In conversation with Heph"],
				required: false,
			},
		],
	},
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("checkbox", { name: "On the pull request" }));
		await userEvent.click(canvas.getByRole("checkbox", { name: "In conversation with Heph" }));
		await expect(args.onDraftChange).toHaveBeenLastCalledWith({
			channels: ["On the pull request", "In conversation with Heph"],
		});
		await userEvent.click(canvas.getByRole("button", { name: "Clear answer" }));
		await expect(args.onDraftChange).toHaveBeenLastCalledWith({});
	},
};

/** Every scale point is a radio the keyboard reaches, and an optional answer can be taken back. */
export const AnswersAScale: Story = {
	play: async ({ canvas, args }) => {
		const scale = canvas.getByRole("radiogroup", {
			name: /How useful is the practice feedback/,
		});
		await userEvent.click(within(scale).getByRole("radio", { name: "4" }));
		await expect(args.onDraftChange).toHaveBeenLastCalledWith({ useful: 4 });
		await expect(scale).toHaveAttribute("aria-required", "true");
		const recommend = canvas.getByRole("radiogroup", { name: /How likely are you to recommend/ });
		await userEvent.click(within(recommend).getByRole("radio", { name: "9" }));
		await expect(args.onDraftChange).toHaveBeenLastCalledWith({ useful: 4, recommend: 9 });
		await userEvent.click(canvas.getByRole("button", { name: "Clear answer" }));
		await expect(args.onDraftChange).toHaveBeenLastCalledWith({ useful: 4 });
	},
};
