import { HephaestusLogoMark, HephaestusWordmark } from "@/components/brand/HephaestusLogo";

export function AboutHeroSection() {
	return (
		<section aria-labelledby="about-hero-heading" className="space-y-6 py-4 text-center">
			<HephaestusLogoMark className="mx-auto size-16" />

			<h1 id="about-hero-heading" className="text-4xl font-bold tracking-display sm:text-5xl">
				About <HephaestusWordmark />
			</h1>
			<p className="mx-auto max-w-2xl text-xl leading-relaxed text-pretty text-muted-foreground">
				Hephaestus helps software teams put good engineering practices into their everyday work. It
				reviews pull requests, issues, code reviews and team discussions as the work happens. Each
				person gets feedback that cites their work and names a next step. Feedback appears on the
				work while it is still open, in a weekly digest, and on each person’s Practice profile. The
				profile shows how each practice develops over time. Work drafted with a coding agent gets
				the same feedback.
			</p>
			<p className="mx-auto max-w-2xl text-lg leading-relaxed text-pretty text-muted-foreground">
				In a conversation, anyone can ask Hephaestus why, ask for an example, or disagree.
			</p>
		</section>
	);
}
