import { HephMark, HephaestusWordmark } from "@/components/brand/HephaestusLogo";

export function AboutHeroSection() {
	return (
		<section aria-labelledby="about-hero-heading" className="space-y-6 py-4 text-center">
			<HephMark className="mx-auto size-16" />

			<h1 id="about-hero-heading" className="text-4xl font-bold tracking-display sm:text-5xl">
				About <HephaestusWordmark />
			</h1>
			<p className="mx-auto max-w-2xl text-xl leading-relaxed text-pretty text-muted-foreground">
				Hephaestus reviews the pull requests, issues, and code reviews your team already writes
				against the engineering practices you choose. It gives each developer feedback that cites
				their work and names a next step.
			</p>
			<p className="mx-auto max-w-2xl text-lg leading-relaxed text-pretty text-muted-foreground">
				Feedback appears on the work itself, on each developer’s private practice page, and in a
				conversation. There, developers can ask why, ask for an example, or disagree. Workspace
				admins can approve feedback before it appears on the work.
			</p>
		</section>
	);
}
