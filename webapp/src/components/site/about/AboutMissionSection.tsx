import { Hammer } from "lucide-react";
import { HephIcon } from "@/components/brand/HephIcon";
import { InstitutionalAttribution } from "@/components/layout/InstitutionalAttribution";
import { Badge } from "@/components/ui/badge";
import { FeatureCard, type FeatureData } from "./FeatureCard";

const FEATURES_DATA: FeatureData[] = [
	{
		icon: <Hammer className="size-5" strokeWidth={1.7} />,
		badge: "Core feature",
		title: "Practice feedback",
		description: "Specific feedback on how the work was done",
		content:
			"Hephaestus reads a contribution and the work around it against the practices a workspace has chosen. Each piece of feedback names the practice it came from and points back to what it saw.",
	},
	{
		icon: <HephIcon size={20} strokeWidth={1.7} animated={false} />,
		badge: "Core feature",
		title: "Talk it through",
		description: "Ask why, push back, or work out the next step",
		content:
			"In chat Hephaestus goes by Heph, and draws on recent project activity, the feedback a developer has received, and any Slack messages or Outline documents their admins connected. Available in the web app and, when connected, in Slack.",
	},
];

export function AboutMissionSection() {
	return (
		<section aria-labelledby="about-mission-heading" className="space-y-12">
			<div>
				<Badge className="mb-4" variant="outline">
					Our purpose
				</Badge>
				<h2 id="about-mission-heading" className="mb-6 text-3xl font-bold">
					The mission
				</h2>

				<p className="text-lg leading-relaxed">
					Developers learn to work well in a team by doing the work and getting feedback on it. That
					feedback is a mentor’s job, whether that is a coach on a university capstone or an
					experienced maintainer on an open-source project, and there is never enough of that
					attention to go round. The developers who need it most often get none. Hephaestus carries
					the routine part so that everyone gets some.
				</p>
			</div>

			<div className="grid grid-cols-1 gap-8 md:grid-cols-2">
				{FEATURES_DATA.map((feature) => (
					<FeatureCard key={feature.title} feature={feature} />
				))}
			</div>

			<div className="rounded-2xl border border-border bg-muted/20 p-6 text-center">
				<h3 className="text-xl font-semibold">Developed at TUM and open source</h3>
				<p className="mx-auto mt-2 mb-6 max-w-2xl text-sm leading-relaxed text-muted-foreground">
					Hephaestus is an MIT-licensed open-source project developed by Applied Education
					Technologies at the Technical University of Munich.
				</p>
				<InstitutionalAttribution />
			</div>
		</section>
	);
}
