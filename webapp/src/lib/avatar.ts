/**
 * Generates initials from a user's name or login.
 * Uses the first letter of the first and last name parts.
 * Falls back to first two characters of login if name is unavailable.
 */
export function getInitials(name?: string | null, login?: string | null): string {
	if (name) {
		const parts = name.trim().split(/\s+/u);
		const first = parts.at(0);
		const last = parts.at(-1);
		if (parts.length >= 2 && first && last) {
			return (first.charAt(0) + last.charAt(0)).toUpperCase();
		}
		return name.slice(0, 2).toUpperCase();
	}
	if (login) {
		return login.slice(0, 2).toUpperCase();
	}
	return "?";
}
