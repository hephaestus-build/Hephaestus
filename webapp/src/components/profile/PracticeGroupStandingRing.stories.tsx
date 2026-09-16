import type { Meta, StoryObj } from "@storybook/react";
import type { PracticeStanding } from "@/api/types.gen";
import { PracticeGroupStandingRing } from "./PracticeGroupStandingRing";

const practice = (slug: string, standing: PracticeStanding["standing"]): PracticeStanding => ({
	slug,
	name: slug.replaceAll("-", " "),
	standing,
	strengths: [],
	toWorkOn: [],
});

const repeat = (count: number, standing: PracticeStanding["standing"]): PracticeStanding[] =>
	Array.from({ length: count }, (_, index) =>
		practice(`${standing.toLowerCase()}-${index}`, standing),
	);

const meta = {
	component: PracticeGroupStandingRing,
	parameters: {
		layout: "centered",
		docs: {
			description: {
				component:
					"The distribution of a group's practices, drawn as one arc per standing in the registry's " +
					"worst-first order. Purely decorative — the same counts appear as text beside it — so it " +
					"is `aria-hidden`. What the stories below pin down is the arc arithmetic, which has three " +
					"cases a two-segment example never reaches.",
			},
		},
	},
	tags: ["autodocs"],
} satisfies Meta<typeof PracticeGroupStandingRing>;

export default meta;
type Story = StoryObj<typeof meta>;
export const SingleSegment: Story = {
	args: { practices: repeat(3, "STRENGTH") },
};

export const TwoSegments: Story = {
	args: { practices: [...repeat(1, "DEVELOPING"), ...repeat(1, "STRENGTH")] },
};
export const AllStandings: Story = {
	args: {
		practices: [
			...repeat(2, "DEVELOPING"),
			...repeat(3, "MIXED"),
			...repeat(4, "STRENGTH"),
			...repeat(2, "NO_OPPORTUNITY"),
			...repeat(1, "NOT_OBSERVED"),
		],
	},
};
export const SliverSegment: Story = {
	args: { practices: [...repeat(39, "STRENGTH"), ...repeat(1, "DEVELOPING")] },
};
export const NoPractices: Story = {
	args: { practices: [] },
};
