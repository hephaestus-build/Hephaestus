import { ScanEye } from "lucide-react";
import type { ReactNode } from "react";

import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";

import type { ReviewReadinessState } from "./review-readiness";
import { REVIEW_SECTIONS, type ReviewSectionId } from "./review-sections";
import { ReviewReadinessBanner } from "./ReviewReadinessBanner";

export interface ReviewPageProps {
	section: ReviewSectionId;
	onSectionChange: (section: ReviewSectionId) => void;
	/**
	 * Absent until the workspace is known. The tabs render meanwhile rather than a spinner: a banner
	 * that guessed at the answer would be reassuring on exactly the evidence it does not have.
	 */
	readiness: ReviewReadinessState | undefined;
	/**
	 * The three tab bodies, as elements. Passing an element does not run it — Base UI mounts only the
	 * open panel — so the sections a reader has not opened cost nothing, which is the whole reason
	 * this page hands its bodies down instead of composing them here.
	 */
	sections: Record<ReviewSectionId, ReactNode>;
}

export function ReviewPage({ section, onSectionChange, readiness, sections }: ReviewPageProps) {
	const active = REVIEW_SECTIONS.find((candidate) => candidate.id === section);

	return (
		<PageLayout>
			<PageHeader icon={<ScanEye />} title="Review settings" description={active?.description} />
			{readiness && <ReviewReadinessBanner readiness={readiness} />}
			<Tabs
				value={section}
				onValueChange={(next) => {
					const opened = REVIEW_SECTIONS.find((candidate) => candidate.id === next);
					if (opened) {
						onSectionChange(opened.id);
					}
				}}
				className="gap-6"
			>
				{/* Wrapping: the labels do not fit on one narrow line, and a tab list that overflows drags
				    the whole page sideways rather than scrolling. `h-auto` is restated under the
				    orientation variant, which otherwise wins with its `h-8`; the triggers keep the
				    primitive's height, so the active one sits inside the track like every other tab list. */}
				<TabsList className="w-full flex-wrap justify-start group-data-[orientation=horizontal]/tabs:h-auto sm:w-fit">
					{REVIEW_SECTIONS.map((candidate) => (
						<TabsTrigger key={candidate.id} value={candidate.id} className="flex-none px-3">
							{candidate.label}
						</TabsTrigger>
					))}
				</TabsList>
				{REVIEW_SECTIONS.map((candidate) => (
					<TabsContent key={candidate.id} value={candidate.id}>
						{sections[candidate.id]}
					</TabsContent>
				))}
			</Tabs>
		</PageLayout>
	);
}
