import {
	Layers,
	ListChecks,
	MessageSquareText,
	MessagesSquare,
	SlidersHorizontal,
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
		name: "Practices",
		text: "Start from a curated catalog or write your own. Each states what good work looks like.",
	},
	{
		icon: MessageSquareText,
		name: "Feedback on the Work",
		text: "A comment on the pull request, merge request, or issue.",
	},
	{
		icon: UserRound,
		name: "Practice Page",
		text: "Each developer’s private view of feedback and progress per practice. Workspace admins see the observations and delivery status behind it.",
	},
	{
		icon: MessagesSquare,
		name: "Conversations",
		text: "A weekly digest you can turn on arrives in the conversation. Developers ask why, ask for an example, or disagree.",
	},
	{
		icon: SlidersHorizontal,
		name: "Feedback Mode",
		text: "Off, Approve first, or Automatic, per workspace, practice group, or practice. Approve first holds feedback on the work until a workspace admin approves it.",
	},
	{
		icon: Layers,
		name: "Your Stack",
		text: "Works with GitHub, GitLab, Slack, and Outline. Your own model provider: OpenAI, Azure OpenAI, or any OpenAI-compatible endpoint. Helm on your Kubernetes.",
	},
];
