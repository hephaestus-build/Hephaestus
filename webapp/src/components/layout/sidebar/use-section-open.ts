import { useState } from "react";

/**
 * Forced open when the reader navigates into the section, freely collapsible otherwise. Adjusted
 * during render, not in an effect, so arriving on a page never paints its section collapsed first.
 */
export function useSectionOpen(onSection: boolean) {
	const [open, setOpen] = useState(onSection);
	const [wasOnSection, setWasOnSection] = useState(onSection);

	if (onSection !== wasOnSection) {
		setWasOnSection(onSection);
		if (onSection) {
			setOpen(true);
		}
	}

	return [open, setOpen] as const;
}
