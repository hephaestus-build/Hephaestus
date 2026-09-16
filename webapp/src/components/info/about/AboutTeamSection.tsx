import type { Contributor } from "@/components/shared/ContributorGrid";
import { Badge } from "@/components/ui/badge";

import { ContributorSection } from "./ContributorSection";
import { type ProjectManager, ProjectManagerCard } from "./ProjectManagerCard";

interface AboutTeamSectionProps {
	projectManager: ProjectManager;
	contributors: Contributor[];
	isContributorsLoading: boolean;
	isContributorsError: boolean;
}

export function AboutTeamSection({
	projectManager,
	contributors,
	isContributorsLoading,
	isContributorsError,
}: AboutTeamSectionProps) {
	return (
		<section aria-labelledby="about-team-heading">
			<Badge className="mb-4" variant="outline">
				Our people
			</Badge>
			<h2 id="about-team-heading" className="mb-10 text-3xl font-bold">
				The team
			</h2>
			<ProjectManagerCard projectManager={projectManager} />
			<ContributorSection
				contributors={contributors}
				isLoading={isContributorsLoading}
				isError={isContributorsError}
			/>
		</section>
	);
}
