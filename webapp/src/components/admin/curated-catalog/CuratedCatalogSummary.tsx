import type { CuratedCatalogSummary as Summary } from "@/api/types.gen";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

export interface CuratedCatalogSummaryProps {
	summary: Summary;
	removedDefaultsToReview: number;
	reviewing?: boolean;
	onReviewChanges: () => void;
}

export function CuratedCatalogSummary({
	summary,
	removedDefaultsToReview,
	reviewing = false,
	onReviewChanges,
}: CuratedCatalogSummaryProps) {
	const updates =
		summary.updatesChangingDetection +
		summary.updatesChangingWordingOnly +
		summary.updatesChangingPresentation;
	const changes = updates + removedDefaultsToReview;
	if (changes === 0) {
		return null;
	}

	return (
		<div className="flex flex-wrap items-center gap-2 rounded-lg border border-warning/50 bg-warning/5 p-4 text-sm">
			<span className="font-medium">
				{changes} Hephaestus {changes === 1 ? "change needs" : "changes need"} review
			</span>
			{summary.updatesChangingDetection > 0 && (
				<Badge variant="warning">
					{summary.updatesChangingDetection}{" "}
					{summary.updatesChangingDetection === 1 ? "update would" : "updates would"} change review
					rules
				</Badge>
			)}
			{summary.updatesChangingWordingOnly > 0 && (
				<Badge variant="secondary">
					{summary.updatesChangingWordingOnly}{" "}
					{summary.updatesChangingWordingOnly === 1 ? "update would" : "updates would"} change
					wording or guidance
				</Badge>
			)}
			{summary.updatesChangingPresentation > 0 && (
				<Badge variant="secondary">
					{summary.updatesChangingPresentation}{" "}
					{summary.updatesChangingPresentation === 1 ? "update would" : "updates would"} change
					group appearance
				</Badge>
			)}
			{removedDefaultsToReview > 0 && (
				<Badge variant="outline">
					{removedDefaultsToReview} {removedDefaultsToReview === 1 ? "entry is" : "entries are"} no
					longer in Hephaestus defaults
				</Badge>
			)}
			{!reviewing && (
				<Button variant="outline" size="sm" className="ml-auto" onClick={onReviewChanges}>
					Review changes
				</Button>
			)}
		</div>
	);
}
