import { Suspense } from "react";

import { PRACTICE_REPOSITORY_SECTION_ID } from "@/components/admin/practices/PracticeRepositoryLink";
import { Skeleton } from "@/components/ui/skeleton";
import { lazyComponent, useLazyComponent } from "@/lib/lazy-component";

// Setting the practice repository is a rare admin action, so its card, dialogs and repository list
// load after Practice setup's own code.
const practiceRepositorySetting = lazyComponent<{ workspaceSlug: string }>(async () => {
	const setting = await import("./-PracticeRepositorySetting");
	return setting.PracticeRepositorySetting;
});

/** Loads the card ahead of its first use, so it then renders in the same pass. */
export const loadPracticeRepositorySetting = practiceRepositorySetting.preload;

export function LazyPracticeRepositorySetting({ workspaceSlug }: { workspaceSlug: string }) {
	const Setting = useLazyComponent(practiceRepositorySetting);
	return (
		// The placeholder holds the card's id, so a link to the card finds it while its code loads.
		<Suspense
			fallback={<Skeleton id={PRACTICE_REPOSITORY_SECTION_ID} className="h-14 w-full rounded-lg" />}
		>
			<Setting workspaceSlug={workspaceSlug} />
		</Suspense>
	);
}
