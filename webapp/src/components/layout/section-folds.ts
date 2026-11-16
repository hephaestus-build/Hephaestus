import { useState } from "react";

const STORAGE_PREFIX = "hephaestus:section-folded:";

/**
 * The reader's stored choice: folded, opened, or none yet. Storage can be unavailable or full; the
 * section then starts in its default and forgets the choice.
 */
function readFolded(key: string): boolean | undefined {
	try {
		const stored = window.localStorage.getItem(STORAGE_PREFIX + key);
		return stored === null ? undefined : stored === "true";
	} catch {
		return undefined;
	}
}

function storeFolded(key: string, folded: boolean) {
	try {
		window.localStorage.setItem(STORAGE_PREFIX + key, String(folded));
	} catch {
		// The choice lasts until the page closes.
	}
}

/**
 * Whether a foldable section is open, remembered in this browser per page and section. A reading
 * preference, not part of what a shared link points at. Until the reader opens or folds it, the
 * section shows `defaultOpen`. Without a key the section is always open.
 */
export function useSectionOpen(
	key: string | undefined,
	{ defaultOpen = true }: { defaultOpen?: boolean } = {},
): [boolean, (open: boolean) => void] {
	const initial = (forKey: string | undefined) => {
		if (forKey === undefined) {
			return true;
		}
		const folded = readFolded(forKey);
		return folded === undefined ? defaultOpen : !folded;
	};
	const [state, setState] = useState(() => ({ key, open: initial(key) }));
	// A key that arrives after the first render, such as one that names the signed-in account, reads
	// its own stored choice.
	if (state.key !== key) {
		setState({ key, open: initial(key) });
	}
	return [
		state.key === key ? state.open : initial(key),
		(next) => {
			setState({ key, open: next });
			if (key !== undefined) {
				storeFolded(key, !next);
			}
		},
	];
}

/** Forgets one stored choice. A story or test starts from it, so none inherits another's fold. */
export function forgetSectionFold(key: string) {
	try {
		window.localStorage.removeItem(STORAGE_PREFIX + key);
	} catch {
		// Nothing was stored.
	}
}
