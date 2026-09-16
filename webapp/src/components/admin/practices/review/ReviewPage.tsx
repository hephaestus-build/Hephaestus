import { ScanEye } from "lucide-react";
import type { ReactNode } from "react";

import { PageHeader } from "@/components/core/PageHeader";
import { PageLayout } from "@/components/core/PageLayout";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";

import type { ReviewRunningState } from "./review-readiness";
import { REVIEW_SECTIONS, type ReviewSectionId } from "./review-sections";
import { ReviewRunningBanner } from "./ReviewRunningBanner";

export interface ReviewPageProps {
	section: ReviewSectionId;
	onSectionChange: (section: ReviewSectionId) => void;
	/**
	 * Absent until the workspace is known. The tabs render meanwhile rather than a spinner: a banner
	 * that guessed at the answer would be reassuring on exactly the evidence it does not have.
	 */
	running: ReviewRunningState | undefined;
	/**
	 * The three tab bodies, as elements. Passing an element does not run it — Base UI mounts only the
	 * open panel — so the sections a reader has not opened cost nothing, which is the whole reason
	 * this page hands its bodies down instead of composing them here.
	 */
	sections: Record<ReviewSectionId, ReactNode>;
}

/**
 * Tabs, not a stack: only the open section is in the document, so a section's data is fetched when
 * somebody asks for it, and the autonomy section's sticky summary strip cannot hang over another
 * section's settings.
 */
export function ReviewPage({ section, onSectionChange, running, sections }: ReviewPageProps) {
	const active = REVIEW_SECTIONS.find((candidate) => candidate.id === section);

	return (
		<PageLayout>
			<PageHeader icon={<ScanEye />} title="Review" description={active?.description} />
			{running && <ReviewRunningBanner running={running} />}
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
				{/* `h-auto` and wrapping: the labels do not fit on one narrow line, and a tab list that
				    overflows drags the whole page sideways rather than scrolling. */}
				<TabsList className="h-auto w-full flex-wrap justify-start sm:w-fit">
					{REVIEW_SECTIONS.map((candidate) => (
						<TabsTrigger key={candidate.id} value={candidate.id} className="h-8 flex-none px-3">
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
