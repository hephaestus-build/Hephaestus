/**
 * Generates a URL-friendly slug from a display name.
 *
 * Rules: lowercase, non-alphanumeric chars replaced with hyphens,
 * consecutive hyphens collapsed, must start with alphanumeric,
 * trimmed to 51 characters. The frontend slug regex is stricter than the
 * backend pattern (`^[a-z0-9][a-z0-9-]{2,50}$`), additionally requiring the
 * slug to end with an alphanumeric character and prohibiting consecutive hyphens.
 */
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
