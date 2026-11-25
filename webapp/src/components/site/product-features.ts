import {
	Layers,
	ListChecks,
	MessageSquareText,
	MessagesSquare,
	Sprout,
	type LucideIcon,
	UserRound,
} from "lucide-react";

export interface ProductFeature {
	icon: LucideIcon;
	name: string;
	text: string;
}

/** The six product features, named the same way in the README, the docs and the webapp. */
export const PRODUCT_FEATURES: ProductFeature[] = [
	{
		icon: ListChecks,
		name: "Your Team’s Practices",
		text: "Start from the catalog or write your own. Each practice describes what good work looks like, such as writing clear issues or testing a change.",
	},
	{
		icon: MessageSquareText,
		name: "Feedback on Your Work",
		text: "Feedback can appear on your pull request, merge request, or issue, on your Practice profile, or in a conversation. By default, comments on the work wait for a workspace admin’s approval and post while the work is open.",
	},
	{
		icon: UserRound,
		name: "Practice Profile",
		text: "When your workspace reviews practices, your Practice profile shows recurring feedback and what is going well in your reviewed work.",
	},
	{
		icon: MessagesSquare,
		name: "Ask Hephaestus",
		text: "Ask why, ask for an example, or disagree, in the web app or, when your workspace uses Slack, in a direct message.",
	},
	{
		icon: Sprout,
		name: "Built for Growth",
		text: "Feedback describes your work and how to improve it, without scores, grades or ranks. AI writes the feedback and can make mistakes, so check it against your work and decide what to do.",
	},
	{
		icon: Layers,
		name: "Connected Services",
		text: "Connect GitHub or GitLab for repository work. Add Slack for conversations and team channels, or Outline for team documents. Each member chooses In-house, Cloud, or No AI for reviews about their work.",
	},
];
