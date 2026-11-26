import {
	Accordion,
	AccordionContent,
	AccordionItem,
	AccordionTrigger,
} from "@/components/ui/accordion";

const faqItems = [
	{
		key: "ai-mentor",
		q: "What does an AI mentor do?",
		a: "It gives you feedback alongside the people who already mentor you, on the practices your team chose, such as writing clear issues or testing a change. If a review finds a problem it can support, Hephaestus can show you the evidence and suggest a next step. You can ask about the feedback in a conversation, and you decide what to do with it.",
	},
	{
		key: "audiences",
		q: "Who is Hephaestus for?",
		a: "Developers who want feedback on their own work and someone to ask why. Team leads and mentors who choose the practices their team values. Educators who use a workspace for a course or a project team, where a workspace admin chooses the practices and can approve comments before they post. Admins who run it on their own Kubernetes with the Helm chart.",
	},
	{
		key: "practice-feedback",
		q: "What is practice feedback?",
		a: "It is feedback on how developers apply engineering practices. A practice is a defined way of working. Examples are scoping a change, writing an issue someone can act on, answering a reviewer, testing, handling failure, and changing dependencies deliberately. Feedback can cite code when a practice concerns it, such as testing or handling failure. A curated set of practices ships with Hephaestus. Each piece of feedback names the practice it came from and points back to what it saw.",
	},
	{
		key: "code-review",
		q: "How does Hephaestus work with code review?",
		a: "Hephaestus comments on the practices behind the work, next to your reviewers’ comments. You decide what to do with each piece of feedback: fix the work, ask why, or disagree. Merge decisions stay with your reviewers.",
	},
	{
		key: "feedback-location",
		q: "Where does feedback appear?",
		a: "It can appear on the work itself, on your Practice profile, or in a conversation with Hephaestus. Feedback on the work waits for a workspace admin’s approval by default. Admins choose which repositories are connected. You can carry on the conversation in the web app or, when Slack is connected, in a direct message.",
	},
	{
		key: "who-sees-feedback",
		q: "Who sees my feedback?",
		a: "Feedback describes your work and how to improve it, without scores, grades or ranks. Your Practice profile shows you the full feedback. Workspace admins see practice results, each linked to the work it is about, and whether feedback was delivered. Those links can identify you even when names are hidden. A workspace owner can let admins see who these records are about, and Hephaestus records each change. Your conversation with Hephaestus stays out of workspace admin pages. An instance admin can include it in an audited export of your data. Feedback on the work is visible to everyone who can see that work, like any review comment.",
	},
	{
		key: "conversation",
		q: "What can I ask Hephaestus?",
		a: "Why a suggestion matters, or for an example. You can also add context Hephaestus missed, or disagree. Hephaestus draws on your recent project activity, the feedback you have received, and selected Outline documents. Hephaestus can use new messages in monitored Slack channels, and you can turn off use of your channel messages. Hephaestus answers when your workspace has a model that fits your AI choice.",
	},
	{
		key: "project-data",
		q: "What project data can Hephaestus use?",
		a: "Workspace admins choose which repositories, Slack channels, and Outline collections to connect. Each member chooses In-house, Cloud or No AI for their work across their workspaces. That choice limits which configured models can process their work. You can turn off use of your Slack channel messages. Check the privacy information published by your instance operator for provider terms.",
	},
	{
		key: "ai-limitations",
		q: "How can I check the feedback?",
		a: "AI writes the feedback and can make mistakes. Each piece of feedback points to the evidence in your work, so check the work it links to, ask a follow-up, or disagree and say why.",
	},
	{
		key: "cost",
		q: "What does it cost?",
		a: "Hephaestus itself is free and MIT-licensed. If you run it yourself you pay your own AI provider, and your hosting costs. The instance admin can set a monthly budget for shared models in each workspace. The workspace admin can set a cap for the workspace’s own provider.",
	},
	{
		key: "access",
		q: "How can I use Hephaestus?",
		a: "Self-host it with the Helm chart on your Kubernetes cluster, or sign in to a deployment your team already uses.",
	},
];

export function LandingFaqSection() {
	return (
		<section
			id="faq"
			aria-labelledby="landing-faq-heading"
			className="w-full bg-linear-to-b from-background to-muted/30 py-12 md:py-20"
		>
			<div className="mx-auto grid w-full max-w-6xl gap-10 px-4 md:px-6 lg:grid-cols-[0.7fr_1.3fr] lg:gap-16">
				<div>
					<h2
						id="landing-faq-heading"
						className="text-3xl font-bold tracking-tight text-balance sm:text-4xl"
					>
						Frequently asked questions
					</h2>
					<p className="mt-4 max-w-md text-muted-foreground">
						What Hephaestus looks at, where the feedback lands, and what it does with your project
						data.
					</p>
				</div>

				<div>
					<Accordion className="w-full">
						{faqItems.map((item) => (
							<AccordionItem key={item.key} value={item.key}>
								<AccordionTrigger className="text-left font-medium">{item.q}</AccordionTrigger>
								<AccordionContent className="text-muted-foreground">{item.a}</AccordionContent>
							</AccordionItem>
						))}
					</Accordion>
				</div>
			</div>
		</section>
	);
}
