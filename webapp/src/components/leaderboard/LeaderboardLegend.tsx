import { InfoIcon } from "@primer/octicons-react";
import { useState } from "react";

import { cn } from "cn";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { getProviderTerms, getPullRequestStateIcon, type ProviderType } from "@/lib/provider";

import { type ActivityBadgeMetadata, getActivityBadgeMetadata } from "./activity-badge-metadata";
import { ScoringExplanationDialog } from "./ScoringExplanationDialog";

export function LeaderboardLegend({ providerType = "GITHUB" }: { providerType?: ProviderType }) {
	const [showScoringModal, setShowScoringModal] = useState(false);
	const badges = getActivityBadgeMetadata(providerType);
	const scoredBadges = badges.filter((badge) => badge.countsTowardScore);
	const contextBadges = badges.filter((badge) => !badge.countsTowardScore);
	const { icon: ReviewedPullRequestIcon } = getPullRequestStateIcon(providerType, "OPEN");
	const reviewedLabel = `Reviewed ${getProviderTerms(providerType).pullRequests.toLowerCase()}`;

	return (
		<>
			<Card>
				<CardHeader>
					<CardTitle>
						<InfoIcon className="inline mr-2 h-4 w-4" /> Activity Legend
					</CardTitle>
					<CardDescription>What counts toward score, and what is just shown.</CardDescription>
				</CardHeader>
				<CardContent>
					<div className="space-y-3">
						<div className="space-y-2">
							<p className="text-sm font-medium">Counts toward score</p>
							<div className="grid grid-cols-1 gap-2">
								<LegendRow
									icon={ReviewedPullRequestIcon}
									colorClass="text-provider-muted-foreground"
									label={reviewedLabel}
								/>
								{scoredBadges.map((badge) => (
									<LegendRow
										key={badge.key}
										icon={badge.icon}
										colorClass={badge.colorClass}
										label={badge.label}
									/>
								))}
							</div>
						</div>

						<div className="space-y-2 pt-2 border-t">
							<p className="text-sm font-medium">Also shown</p>
							<div className="grid grid-cols-1 gap-2">
								{contextBadges.map((badge) => (
									<LegendRow
										key={badge.key}
										icon={badge.icon}
										colorClass={badge.colorClass}
										label={badge.label}
									/>
								))}
							</div>
						</div>

						<div className="pt-2 border-t">
							<p className="text-sm text-provider-muted-foreground mb-2">
								Only reviews affect score. Everything else is shown for context.
							</p>
							<Button
								variant="outline"
								size="sm"
								className="text-provider-link-foreground"
								onClick={() => setShowScoringModal(true)}
							>
								How scoring works
							</Button>
						</div>
					</div>
				</CardContent>
			</Card>

			<ScoringExplanationDialog
				open={showScoringModal}
				onOpenChange={setShowScoringModal}
				providerType={providerType}
			/>
		</>
	);
}

interface LegendRowProps {
	icon: ActivityBadgeMetadata["icon"];
	colorClass: string;
	label: string;
}

function LegendRow({ icon: Icon, colorClass, label }: LegendRowProps) {
	return (
		<div className={cn("flex items-center gap-2", colorClass)}>
			<Icon className="h-4 w-4" size={16} />
			<span>{label}</span>
		</div>
	);
}
