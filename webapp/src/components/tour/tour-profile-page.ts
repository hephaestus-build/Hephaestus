import { type ComponentType, createContext } from "react";

import type { PracticeProfilePageProps } from "@/components/practice-profile/PracticeProfilePage";

/**
 * The Practice profile page the tour shows as its example. The page hands itself in, so the tour's
 * scenes render it without importing the page that mounts the tour.
 */
export const TourProfilePageContext = createContext<ComponentType<PracticeProfilePageProps> | null>(
	null,
);
