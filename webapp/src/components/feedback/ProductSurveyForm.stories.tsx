import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { Dialog, DialogContent } from "@/components/ui/dialog";
import { Stateful } from "@/stories/stateful";
import { productSurvey } from "./product-survey-fixtures";
import { ProductSurveyForm } from "./ProductSurveyForm";

const meta = {
	title: "Surveys/Product survey form",
	component: ProductSurveyForm,
	args: {
		survey: productSurvey,
		answers: {},
		isSubmitting: false,
		onAnswersChange: fn(),
		onBack: fn(),
		onSubmit: fn(),
		onDismiss: fn(),
	},
	render: (args) => (
		<Stateful initial={args.answers}>
			{(answers, setAnswers) => (
				<ProductSurveyForm
					{...args}
					answers={answers}
					onAnswersChange={(next) => {
						args.onAnswersChange(next);
						setAnswers(next);
					}}
				/>
			)}
		</Stateful>
	),
	decorators: [
		(Story) => (
			<Dialog open>
				<DialogContent>
					<Story />
				</DialogContent>
			</Dialog>
		),
	],
	tags: ["autodocs"],
} satisfies Meta<typeof ProductSurveyForm>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Default: Story = {};
export const Sending: Story = { args: { isSubmitting: true, answers: { useful: "4" } } };
export const Error: Story = {
	args: { error: "Couldn't send. Your draft is still here.", answers: { useful: "4" } },
};

export const OptionalChoice: Story = {
	args: {
		survey: {
			...productSurvey,
			questions: [
				{
					id: "priority",
					prompt: "Which improvement matters most to you?",
					type: "SINGLE_CHOICE",
					options: [
						"Clearer explanations of practice feedback",
						"More control over when I receive feedback",
					],
					required: false,
				},
			],
		},
	},
	globals: { viewport: { value: "reflow" } },
	parameters: { chromatic: { viewports: [320] } },
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("dialog"));
		const option = dialog.getByRole("radio", { name: "Clearer explanations of practice feedback" });
		await userEvent.click(option);
		await expect(option).toBeChecked();
		await expect(args.onAnswersChange).toHaveBeenLastCalledWith({
			priority: "Clearer explanations of practice feedback",
		});
		await userEvent.click(dialog.getByRole("button", { name: "Clear answer" }));
		await expect(option).not.toBeChecked();
		await expect(args.onAnswersChange).toHaveBeenLastCalledWith({});
		await expect(dialog.getByRole("button", { name: "Submit response" })).toBeEnabled();
	},
};
