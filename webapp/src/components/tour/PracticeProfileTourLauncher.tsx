import { useState } from "react";

import { practiceProfileTourSections } from "./practice-profile-tour";
import { requestTourStart, useTourProgress } from "./tour-launcher-store";
import { TourLauncher } from "./TourLauncher";

/**
 * Local demo: Heph's launcher on a page other than the profile. The tour runs on the profile, so a
 * part started here opens the profile and the tour there at that part.
 */
export function PracticeProfileTourLauncher({ onGoToProfile }: { onGoToProfile: () => void }) {
	const [open, setOpen] = useState(false);
	const progress = useTourProgress();
	const sections = practiceProfileTourSections();
	const parts = sections.flatMap((section) => section.parts);
	return (
		<TourLauncher
			sections={sections}
			done={progress.done}
			finished={progress.finished || parts.every((part) => progress.done.includes(part.id))}
			open={open}
			onOpenChange={setOpen}
			onStart={(part) => {
				const id = part ?? parts[0]?.id;
				if (id !== undefined) {
					requestTourStart(id);
				}
				onGoToProfile();
			}}
		/>
	);
}
