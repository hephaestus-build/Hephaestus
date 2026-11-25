import { HephaestusLogoMark, HephaestusWordmark } from "@/components/brand/HephaestusLogo";

export function AboutHeroSection() {
	return (
		<section aria-labelledby="about-hero-heading" className="space-y-6 py-4 text-center">
			<HephaestusLogoMark className="mx-auto size-16" />

			<h1 id="about-hero-heading" className="text-4xl font-bold tracking-display sm:text-5xl">
				About <HephaestusWordmark />
			</h1>
			<p className="mx-auto max-w-2xl text-xl leading-relaxed text-pretty text-muted-foreground">
				Hephaestus is the open-source AI mentor for software engineering practices. It reads your
				pull requests, merge requests, issues and code reviews where they happen, and checks them
				against the practices that your team chose. When a review finds a problem it can support,
				Hephaestus can point to the work, explain what to improve, and suggest a next step. An
				observation can say when the evidence could not settle a question.
			</p>
			<p className="mx-auto max-w-2xl text-lg leading-relaxed text-pretty text-muted-foreground">
				In a conversation, you can ask Hephaestus why, ask for an example, or disagree. Your
				feedback has no score, grade or rank. Hephaestus is software, and it can be wrong.
			</p>
		</section>
	);
}
