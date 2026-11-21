import { Suspense } from "react";

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
		<Suspense fallback={<Skeleton className="h-14 w-full rounded-lg" />}>
			<Setting workspaceSlug={workspaceSlug} />
		</Suspense>
	);
}
