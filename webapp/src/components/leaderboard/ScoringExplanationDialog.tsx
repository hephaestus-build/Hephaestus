import { GraphIcon } from "@primer/octicons-react";

import {
	Dialog,
	DialogContent,
	DialogDescription,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog";
import { getProviderTerms, type ProviderType } from "@/lib/provider/provider-terms";

interface ScoringExplanationDialogProps {
	open: boolean;
	onOpenChange: (open: boolean) => void;
	providerType?: ProviderType;
}

export function ScoringExplanationDialog({
	open,
	onOpenChange,
	providerType = "GITHUB",
}: ScoringExplanationDialogProps) {
	const terms = getProviderTerms(providerType);

	return (
		<Dialog open={open} onOpenChange={onOpenChange}>
			<DialogContent className="max-w-2xl">
				<DialogHeader>
					<DialogTitle className="flex items-center gap-2">
						<GraphIcon className="h-5 w-5" />
						Leaderboard Scoring Formula
					</DialogTitle>
					<DialogDescription>How your contribution score is calculated</DialogDescription>
				</DialogHeader>

				<div className="space-y-3 text-sm text-provider-muted-foreground">
					<p>
						The score approximates your contribution activity by evaluating your review interactions
						and the complexity of the {terms.pullRequests.toLowerCase()} you've reviewed.
						<span className="font-medium"> Change requests are valued highest</span>, followed by
						approvals and comments. The score increases with the number of review interactions.
					</p>

					<p>
						{terms.pullRequest} complexity &mdash; based on factors like changed files, commits,
						additions, and deletions &mdash; also enhances your score;
						<span className="font-medium">
							{" "}
							more complex {terms.pullRequests.toLowerCase()} contribute more
						</span>
						.
					</p>

					<div className="rounded-md bg-provider-muted p-4 font-mono text-xs">
						<p className="font-medium">Score Calculation Formula</p>
						<p className="mt-1">
							score = (10 × interactionScore × complexityScore) / (interactionScore +
							complexityScore)
						</p>

						<p className="mt-3 font-medium">Where:</p>
						<p>
							interactionScore = approvalScore + changesRequestedScore + commentScore +
							issueCommentScore
						</p>
						<ul className="mt-1 ml-5 list-disc space-y-0.5">
							<li>approvalScore = 2.0 × codeReviewBonus (for each approval)</li>
							<li>changesRequestedScore = 2.5 × codeReviewBonus (for each change request)</li>
							<li>commentScore = 1.5 × codeReviewBonus (for each comment)</li>
						</ul>

						<p className="mt-3 font-medium">Complexity Score:</p>
						<p>
							complexityScore = ((changedFiles × 3) + (commits × 0.5) + additions + deletions) / 10
						</p>
						<ul className="mt-1 ml-5 list-disc space-y-0.5">
							<li>Simple: 1 point (complexityScore &lt; 10)</li>
							<li>Medium: 3 points (complexityScore &lt; 50)</li>
							<li>Large: 7 points (complexityScore &lt; 100)</li>
							<li>X-Large: 17 points (complexityScore &lt; 300)</li>
							<li>XX-Large: 33 points (complexityScore ≥ 300)</li>
						</ul>
					</div>

					<p>
						The final score balances your interactions with the complexity of the work reviewed,
						highlighting both your engagement and the difficulty of the tasks you've undertaken.
						This score reflects your impact but does not directly measure time invested or work
						quality.
					</p>
				</div>
			</DialogContent>
		</Dialog>
	);
}
