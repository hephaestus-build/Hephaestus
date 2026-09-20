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
		a: "Feedback on how the work was done rather than on the code itself. A practice is a defined way of working — scoping a change, writing an issue someone can act on, answering a reviewer, testing, handling failure, changing dependencies deliberately. A curated set of practices ships with Hephaestus, and each piece of feedback names the practice it came from and points back to what it saw.",
	},
	{
		key: "replaces-review",
		q: "Does this replace code review?",
		a: "No, and it does not replace a mentor either. It does not approve a change for merge or grade anyone. It carries the routine feedback nobody has time to give everyone, and leaves the harder judgement and the relationships to people.",
	},
	{
		key: "feedback-location",
		q: "Where does feedback appear?",
		a: "Wherever you are most likely to read it: on the work itself, on your own practice pages in Hephaestus, or in your next conversation with it. Admins choose which repositories are connected, and you can carry on the conversation in the web app or, when Slack is connected, in a direct message.",
	},
	{
		key: "heph-conversation",
		q: "What can I ask it?",
		a: "Why a suggestion matters, or what it did not know when it wrote it. In chat Hephaestus goes by Heph, and it draws on your recent project activity, the feedback you have received, and any Slack messages or Outline documents your admins connected.",
	},
	{
		key: "project-data",
		q: "What project data can Hephaestus use?",
		a: "Only what workspace admins connect: specific repositories, and optionally selected Slack channels and Outline collections. Which AI provider processes that data is set per deployment, so check the privacy information published by whoever operates yours.",
	},
	{
		key: "ai-limitations",
		q: "Can the feedback be wrong?",
		a: "Yes. It is written by an AI model, so it can miss context or suggest something unhelpful. Check the work it links to, ask Heph a follow-up, then decide for yourself.",
	},
	{
		key: "cost",
		q: "What does it cost?",
		a: "Hephaestus itself is free and MIT-licensed. If you run it yourself you pay your own AI provider, and each workspace runs under a monthly spending cap its admin sets.",
	},
	{
		key: "access",
		q: "How can I use Hephaestus?",
		a: "Through a deployment your workspace already has access to, or by self-hosting it. The TUM-operated deployment has its own sign-in and workspace access rules.",
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
