import { HephMark, HephaestusWordmark } from "@/components/brand/HephaestusLogo";

export function AboutHeroSection() {
	return (
		<section aria-labelledby="about-hero-heading" className="space-y-6 py-4 text-center">
			<HephMark className="mx-auto size-16" />

			<h1 id="about-hero-heading" className="text-4xl font-bold tracking-display sm:text-5xl">
				About <HephaestusWordmark />
			</h1>
			<p className="mx-auto max-w-2xl text-xl leading-relaxed text-pretty text-muted-foreground">
				Hephaestus is an open-source AI mentor for software teams. It checks the issues, pull and
				merge requests, and reviews that your team already writes against the engineering practices
				that your workspace adopts. Each developer gets feedback with evidence and, where something
				needs attention, a next step.
			</p>
		</section>
	);
}
