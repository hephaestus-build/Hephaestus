import { InstitutionalAttribution } from "@/components/layout/InstitutionalAttribution";

export function LandingProjectOriginsSection() {
	return (
		<section
			aria-labelledby="project-origins-heading"
			className="w-full border-y border-border bg-muted/20 py-12"
		>
			<div className="mx-auto grid w-full max-w-5xl items-center gap-6 px-4 md:grid-cols-[minmax(0,1fr)_auto] md:px-6">
				<div className="text-center md:text-left">
					<h2 id="project-origins-heading" className="text-xl font-semibold">
						Developed at TUM and open source
					</h2>
					<p className="mt-2 max-w-2xl text-sm leading-relaxed text-muted-foreground">
						Hephaestus is an MIT-licensed open-source project developed by Applied Education
						Technologies at the Technical University of Munich.
					</p>
				</div>
				<div className="justify-self-center md:justify-self-auto">
					<InstitutionalAttribution />
				</div>
			</div>
		</section>
	);
}
