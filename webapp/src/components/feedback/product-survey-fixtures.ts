import type { Survey } from "@/api/types.gen";
import { STORY_NOW } from "@/components/common/story-clock";

export const productSurvey = {
	id: "11111111-1111-1111-1111-111111111111",
	title: "Help improve Hephaestus",
	description: "Tell the instance administrators what would make reviews more useful.",
	questions: [
		{
			id: "useful",
			prompt: "How useful are reviews? (1 = not useful, 5 = very useful)",
			type: "RATING",
			options: [],
			required: true,
		},
		{ id: "improve", prompt: "What should improve?", type: "TEXT", options: [], required: false },
	],
	startsAt: new Date(STORY_NOW),
	active: true,
} satisfies Survey;
