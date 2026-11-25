import { OutboundMark } from "@/components/common/outbound-link";
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
					Developers learn engineering practices by doing the work and getting feedback on it, and
					the people who give that feedback have little time. In 37 Apache projects, a{" "}
					<a
						href="https://doi.org/10.1145/3540250.3549167"
						target="_blank"
						rel="noopener noreferrer"
						className="underline underline-offset-4 hover:text-highlight"
					>
						2022 study
						<OutboundMark />
					</a>{" "}
					found that only about a quarter of pull requests received a comment that explained a
					suggestion or a fix. Hephaestus adds feedback on everyday work alongside the guidance that
					reviewers and mentors give.
				</p>
			</div>

			<div>
				<h2 className="mb-6 text-2xl font-bold">Features</h2>
				<ProductFeatureList features={PRODUCT_FEATURES} />
			</div>

			<div className="rounded-2xl border border-border bg-muted/20 p-6 text-center">
				<h2 className="text-xl font-semibold">Project origins</h2>
				<p className="mx-auto mt-2 mb-6 max-w-2xl text-sm leading-relaxed text-muted-foreground">
					Developed at the Technical University of Munich. There, Hephaestus is the research
					platform for a study of whether and how AI-generated feedback improves developers’
					application of engineering practices. Applied Education Technologies maintains Hephaestus
					under the MIT license.
				</p>
				<InstitutionalAttribution />
			</div>
		</section>
	);
}
