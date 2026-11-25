import {
	Layers,
	ListChecks,
	MessageSquareText,
	MessagesSquare,
	ShieldCheck,
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
		name: "Practices Your Team Chose",
		text: "Start from the catalog or write your own. Each practice states what good work looks like. For each practice, feedback can be off. Feedback on the work can wait for a workspace admin’s approval or go out automatically.",
	},
	{
		icon: MessageSquareText,
		name: "Feedback on the Work",
		text: "A note on the pull request, merge request, or issue. By default, it posts only while the work is open. It points to the evidence, says why, and suggests a next step.",
	},
	{
		icon: ShieldCheck,
		name: "Feedback Grounded in Your Work",
		text: "A review checks its evidence before feedback can go out. If it cannot capture the evidence it needs, it records no observation for that practice.",
	},
	{
		icon: UserRound,
		name: "Practice Profile",
		text: "See patterns across your work, what is going well, and where you stand on each practice. No score, grade, or rank.",
	},
	{
		icon: MessagesSquare,
		name: "Ask Hephaestus",
		text: "Ask why, ask for an example, or disagree, in the web app or in a Slack direct message. You can turn on a weekly digest in your conversation in the app.",
	},
	{
		icon: Layers,
		name: "Your Stack",
		text: "Works with GitHub, GitLab, Slack, and Outline. Connect OpenAI, Azure OpenAI, Anthropic, or another provider that supports the required API. Helm on your Kubernetes.",
	},
];
