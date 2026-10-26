import {
	Layers,
	ListChecks,
	MessageSquareText,
	MessagesSquare,
	SlidersHorizontal,
	UserRound,
} from "lucide-react";

/** The six product features, named the same way in the README, the docs and the webapp. */
const FEATURES = [
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

export function ProductFeatureList() {
	return (
		<ul className="grid grid-cols-1 gap-x-8 gap-y-6 sm:grid-cols-2 lg:grid-cols-3">
			{FEATURES.map(({ icon: Icon, name, text }) => (
				<li key={name} className="border-t border-border pt-5">
					<div className="flex items-center gap-3">
						<span
							className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-mentor/10 text-mentor"
							aria-hidden="true"
						>
							<Icon className="size-4.5" />
						</span>
						<h3 className="text-base font-semibold">{name}</h3>
					</div>
					<p className="mt-3 text-sm leading-relaxed text-pretty text-muted-foreground">{text}</p>
				</li>
			))}
		</ul>
	);
}
