import { InstitutionalAttribution } from "@/components/layout/InstitutionalAttribution";
import { PRODUCT_FEATURES } from "@/components/site/product-features";
import { ProductFeatureList } from "@/components/site/ProductFeatureList";

export function AboutMissionSection() {
	return (
		<section aria-labelledby="about-mission-heading" className="space-y-12">
			<div>
				<h2 id="about-mission-heading" className="mb-6 text-3xl font-bold">
					Why Hephaestus
				</h2>

				<p className="text-lg leading-relaxed">
					Developers learn engineering practices by doing the work and getting feedback on it. That
					feedback comes from reviewers and experienced colleagues, and their time is scarce. In 37
					Apache projects, a{" "}
					<a
						href="https://doi.org/10.1145/3540250.3549167"
						target="_blank"
						rel="noopener noreferrer"
						className="underline underline-offset-4 hover:text-mentor"
					>
						2022 study
						<span className="sr-only"> (opens in a new tab)</span>
					</a>{" "}
					found that only about a quarter of pull requests received an implicit-mentoring comment.
					Such a comment explains a suggestion, an instruction, or a way to fix an error. Hephaestus
					gives feedback on every piece of work it can assess, so experienced people can spend their
					time where judgment and relationships matter.
				</p>
			</div>

			<div>
				<h2 className="mb-6 text-2xl font-bold">Features</h2>
				<ProductFeatureList features={PRODUCT_FEATURES} />
			</div>

			<div>
				<h2 className="mb-4 text-2xl font-bold">Where Hephaestus is going</h2>
				<p className="text-lg leading-relaxed">
					Practices apply to everyone who contributes to a project, people and coding agents alike.
					The same evidence-backed feedback can steer a coding agent, and it shows a team which
					practices it actually applies, so the team can refine them.
				</p>
			</div>

			<div className="rounded-2xl border border-border bg-muted/20 p-6 text-center">
				<h2 className="text-xl font-semibold">Project origins</h2>
				<p className="mx-auto mt-2 mb-6 max-w-2xl text-sm leading-relaxed text-muted-foreground">
					Developed at the Technical University of Munich. There, Hephaestus is the research
					platform for a study of how AI-generated feedback can improve developers’ application of
					engineering practices. Applied Education Technologies maintains Hephaestus under the MIT
					license.
				</p>
				<InstitutionalAttribution />
			</div>
		</section>
	);
}
