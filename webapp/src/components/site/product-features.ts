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
		text: "Start from the catalog or write your own. Each practice states what good work looks like. For each practice, feedback is Off, waits for a workspace admin’s approval, or goes out automatically.",
	},
	{
		icon: MessageSquareText,
		name: "Feedback on the Work",
		text: "A note on the pull request, merge request, or issue while it is still open. It cites the exact place, says why, and names a next step.",
	},
	{
		icon: ShieldCheck,
		name: "Speaks Only With Evidence",
		text: "When the evidence does not support a point, Hephaestus says nothing. When it could not check a practice, it says so. It never turns a missing check into a claim about your work.",
	},
	{
		icon: UserRound,
		name: "Practice Profile",
		text: "Your private page for each practice: patterns across your work, what is going well, and where you stand. No score, grade, or rank.",
	},
	{
		icon: MessagesSquare,
		name: "Ask Hephaestus",
		text: "Ask why, ask for an example, or disagree, in the web app or in a Slack direct message. A weekly digest you can turn on arrives there too.",
	},
	{
		icon: Layers,
		name: "Your Stack",
		text: "Works with GitHub, GitLab, Slack, and Outline. Your own model provider: OpenAI, Azure OpenAI, or any OpenAI-compatible endpoint. Helm on your Kubernetes.",
	},
];
