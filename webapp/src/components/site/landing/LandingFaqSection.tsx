import {
	Accordion,
	AccordionContent,
	AccordionItem,
	AccordionTrigger,
} from "@/components/ui/accordion";

const faqItems = [
	{
		key: "practice-feedback",
		q: "What is practice feedback?",
		a: "It is feedback on how developers apply engineering practices. A practice is a defined way of working. Examples are scoping a change, writing an issue someone can act on, answering a reviewer, testing, handling failure, and changing dependencies deliberately. Feedback can cite code when a practice concerns it, such as testing or handling failure. A curated set of practices ships with Hephaestus. Each piece of feedback names the practice it came from and points back to what it saw.",
	},
	{
		key: "code-review",
		q: "How does Hephaestus work with code review?",
		a: "Hephaestus comments on the practices behind the work, next to your reviewers’ comments. Developers decide what to do with feedback: fix the work, ask why, or disagree. Merge decisions stay with your reviewers.",
	},
	{
		key: "feedback-location",
		q: "Where does feedback appear?",
		a: "It can appear on the work itself, on each developer’s private practice page, or in your next conversation with Hephaestus. Feedback on the work waits for a workspace admin’s approval by default. Admins choose which repositories are connected. You can carry on the conversation in the web app or, when Slack is connected, in a direct message.",
	},
	{
		key: "heph-conversation",
		q: "What can I ask Hephaestus?",
		a: "Why a suggestion matters, or for an example. You can also add context Hephaestus missed, or disagree. Hephaestus draws on your recent project activity, the feedback you have received, and selected Outline documents. Slack channel messages are used only when you allow it.",
	},
	{
		key: "project-data",
		q: "What project data can Hephaestus use?",
		a: "Only what workspace admins connect: specific repositories, and optionally selected Slack channels and Outline collections. Each member chooses In-house, Cloud or No AI for their work across their workspaces. That choice limits which configured models can process their work. Slack channel messages are used only with the member’s permission. Check the privacy information published by your instance operator for provider terms.",
	},
	{
		key: "ai-limitations",
		q: "Can the feedback be wrong?",
		a: "Yes. It is written by an AI model, so it can miss context or suggest something unhelpful. Check the work it links to, ask Hephaestus a follow-up, or disagree and say why.",
	},
	{
		key: "cost",
		q: "What does it cost?",
		a: "Hephaestus itself is free and MIT-licensed. If you run it yourself you pay your own AI provider, and your hosting costs. The instance admin sets each workspace’s monthly cap for shared models. The workspace admin sets the cap for the workspace’s own provider.",
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
			className="w-full bg-gradient-to-b from-background to-muted/30 py-12 md:py-20"
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
