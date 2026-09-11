import type { Survey, SurveyInvitation, SurveyResponse, SurveySummary } from "@/api/types.gen";
import { daysAfter, daysBefore, hoursBefore, STORY_NOW } from "@/components/common/story-clock";

export const surveyQuestions = [
	{
		id: "useful",
		prompt: "How useful is the practice feedback you receive?",
		type: "RATING",
		options: [],
		required: true,
		allowOther: false,
		lowLabel: "Not useful",
		highLabel: "Very useful",
	},
	{
		id: "channel",
		prompt: "Where do you read feedback most often?",
		type: "SINGLE_CHOICE",
		options: ["On the pull request", "On my practice page", "In conversation with Heph"],
		required: false,
		allowOther: true,
	},
	{
		id: "recommend",
		prompt: "How likely are you to recommend Hephaestus to another team?",
		type: "NPS",
		options: [],
		required: false,
		allowOther: false,
	},
	{
		id: "improve",
		prompt: "What would make it more useful?",
		type: "TEXT",
		options: [],
		required: false,
		allowOther: false,
	},
] satisfies Survey["questions"];

export const surveyInvitation = {
	id: "11111111-1111-1111-1111-111111111111",
	title: "Help improve practice feedback",
	description: "Four quick questions to decide what the next release should focus on.",
	questions: surveyQuestions,
	endsAt: daysAfter(6),
	seen: true,
} satisfies SurveyInvitation;

export const adminSurvey = {
	id: surveyInvitation.id,
	title: surveyInvitation.title,
	description: surveyInvitation.description,
	questions: surveyQuestions,
	startsAt: daysBefore(2),
	endsAt: daysAfter(6),
	active: true,
	createdAt: daysBefore(2),
	createdBy: { id: 1, displayName: "Ada Lovelace", email: "ada@example.org" },
	participation: { invited: 42, responded: 17, declined: 4 },
} satisfies Survey;

export const scheduledSurvey = {
	...adminSurvey,
	id: "22222222-2222-2222-2222-222222222222",
	title: "Onboarding check-in",
	workspace: { id: 7, slug: "acme", displayName: "Acme" },
	startsAt: daysAfter(3),
	endsAt: undefined,
	createdAt: hoursBefore(5),
	participation: { invited: 0, responded: 0, declined: 0 },
} satisfies Survey;

export const pausedSurvey = {
	...adminSurvey,
	id: "33333333-3333-3333-3333-333333333333",
	title: "Mentor conversations",
	active: false,
	createdAt: daysBefore(10),
	participation: { invited: 12, responded: 3, declined: 1 },
} satisfies Survey;

export const endedSurvey = {
	...adminSurvey,
	id: "44444444-4444-4444-4444-444444444444",
	title: "Release 0.70 retrospective",
	startsAt: daysBefore(40),
	endsAt: daysBefore(12),
	createdAt: daysBefore(40),
	participation: { invited: 80, responded: 51, declined: 9 },
} satisfies Survey;

export const surveySummary = {
	participation: adminSurvey.participation,
	questions: [
		{
			questionId: "useful",
			answered: 17,
			counts: [
				{ value: "1", count: 1 },
				{ value: "2", count: 2 },
				{ value: "3", count: 4 },
				{ value: "4", count: 6 },
				{ value: "5", count: 4 },
			],
			average: 3.6,
		},
		{
			questionId: "channel",
			answered: 17,
			counts: [
				{ value: "On the pull request", count: 9 },
				{ value: "On my practice page", count: 3 },
				{ value: "In conversation with Heph", count: 2 },
			],
			other: 3,
		},
		{
			questionId: "recommend",
			answered: 14,
			counts: [0, 0, 0, 1, 0, 1, 2, 2, 3, 3, 2].map((count, value) => ({
				value: String(value),
				count,
			})),
			average: 7.9,
			score: 21,
		},
		{ questionId: "improve", answered: 9, counts: [] },
	],
} satisfies SurveySummary;

export const surveyResponses = [
	{
		id: "aaaaaaaa-0000-0000-0000-000000000001",
		account: { id: 2, displayName: "Grace Hopper", email: "grace@example.org" },
		workspace: { id: 7, slug: "acme", displayName: "Acme" },
		status: "RESPONDED",
		answers: [
			{ questionId: "useful", rating: 4 },
			{ questionId: "channel", choices: ["On the pull request"] },
			{ questionId: "recommend", rating: 9 },
			{ questionId: "improve", text: "Shorter feedback on small pull requests." },
		],
		decidedAt: hoursBefore(3),
	},
	{
		id: "aaaaaaaa-0000-0000-0000-000000000002",
		account: { id: 3, displayName: "Linus Torvalds" },
		workspace: { id: 7, slug: "acme", displayName: "Acme" },
		status: "DECLINED",
		decidedAt: hoursBefore(20),
	},
	{
		id: "aaaaaaaa-0000-0000-0000-000000000003",
		status: "RESPONDED",
		answers: [{ questionId: "useful", rating: 2 }],
		decidedAt: new Date(STORY_NOW - 2 * 86_400_000),
	},
] satisfies SurveyResponse[];
