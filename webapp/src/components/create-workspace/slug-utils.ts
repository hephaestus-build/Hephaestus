/** Generates a suggested workspace slug. The creation schema validates the result. */
export function generateSlug(displayName: string): string {
	return (
		displayName
			// Strip diacritics so "Ünïcödé" → "Unicode" instead of garbled fragments
			.normalize("NFD")
			.replaceAll(/[\u0300-\u036F]/gu, "")
			.toLowerCase()
			.replaceAll(/[^a-z0-9]+/gu, "-")
			.replace(/^-+/u, "")
			.replace(/-+$/u, "")
			.slice(0, 51)
			// Re-strip trailing hyphens in case truncation introduced one
			.replace(/-+$/u, "") || ""
	);
}
