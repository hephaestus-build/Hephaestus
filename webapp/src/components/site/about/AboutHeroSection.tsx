import { HephaestusLogoMark, HephaestusWordmark } from "@/components/brand/HephaestusLogo";

export function AboutHeroSection() {
	return (
		<section aria-labelledby="about-hero-heading" className="space-y-6 py-4 text-center">
			<HephaestusLogoMark className="mx-auto size-16" />

			<h1 id="about-hero-heading" className="text-4xl font-bold tracking-display sm:text-5xl">
				About <HephaestusWordmark />
			</h1>
			<p className="mx-auto max-w-2xl text-xl leading-relaxed text-pretty text-muted-foreground">
				Hephaestus is an AI mentor that helps software developers grow in the engineering practices
				their team values.
			</p>
			<p className="mx-auto max-w-2xl text-lg leading-relaxed text-pretty text-muted-foreground">
				Developers grow by doing real work and getting feedback from people who know the craft:
				senior developers, reviewers, teachers and team leads. Hephaestus gives you feedback
				alongside them, on the practices your team chose. If a review finds a problem it can
				support, Hephaestus can show you the evidence and suggest a next step. You can ask about the
				feedback in a conversation, and you decide what to do with it.
			</p>
		</section>
	);
}
