import { AlertCircle, Users } from "lucide-react";

import { type Contributor, ContributorGrid } from "@/components/common/ContributorGrid";

interface ContributorSectionProps {
	contributors: Contributor[];
	isLoading: boolean;
	isError: boolean;
}

export function ContributorSection({ contributors, isLoading, isError }: ContributorSectionProps) {
	return (
		<div className="space-y-6">
			<div className="mb-4 flex items-center gap-2">
				<Users className="h-5 w-5 text-primary" />
				<h3 className="text-xl font-bold">Contributors</h3>
			</div>
			<p className="mb-8 text-muted-foreground">
				Hephaestus is built by students, researchers, and open-source contributors. These are the
				people who have shaped it so far.
			</p>

			<ContributorGrid
				contributors={contributors}
				isLoading={isLoading}
				layout="comfortable"
				size="md"
			/>

			{isError && (
				<div className="rounded-lg border border-muted bg-gradient-to-br from-background to-muted/30 p-6 text-center sm:p-8">
					<AlertCircle className="mx-auto mb-4 h-8 w-8 text-destructive" />
					<h4 className="mb-2 text-lg font-medium">Contributor data unavailable</h4>
					<p className="text-muted-foreground">
						We’re having trouble reaching our contributor information. Please check back soon.
					</p>
				</div>
			)}
		</div>
	);
}
