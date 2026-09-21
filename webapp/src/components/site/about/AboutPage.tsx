import type { Contributor } from "@/components/common/ContributorGrid";
import { Separator } from "@/components/ui/separator";

import { AboutCallToActionSection } from "./AboutCallToActionSection";
import { AboutHeroSection } from "./AboutHeroSection";
import { AboutMissionSection } from "./AboutMissionSection";
import { AboutTeamSection } from "./AboutTeamSection";
import type { ProjectManager } from "./ProjectManagerCard";

interface AboutPageProps {
	isPending: boolean;
	isError: boolean;
	error?: Error;
	projectManager: ProjectManager;
	otherContributors: Contributor[];
}

export function AboutPage({
	isPending,
	isError,
	projectManager,
	otherContributors,
}: AboutPageProps) {
	return (
		<div className="mx-auto max-w-4xl space-y-16">
			<AboutHeroSection />
			<AboutMissionSection />
			<Separator />
			<AboutTeamSection
				projectManager={projectManager}
				contributors={otherContributors}
				isContributorsLoading={isPending}
				isContributorsError={isError}
			/>
			<AboutCallToActionSection />
		</div>
	);
}
