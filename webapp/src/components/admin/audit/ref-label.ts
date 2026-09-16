import { firstNonBlank, hasText } from "@/lib/text";

export interface AuditRef {
	id?: number;
	displayName?: string;
	email?: string;
}

/** Falls back to `#id`: audit rows outlive the accounts they name. */
export function refLabel(ref: AuditRef | undefined, id: number | undefined): string | null {
	if (ref) {
		return firstNonBlank(ref.displayName, ref.email) ?? `#${ref.id}`;
	}
	if (id != null) {
		return `#${id}`;
	}
	return null;
}

/** The workspace by name when the caller resolved one, else by id alone. */
export function workspaceLabel(id: number, name: string | undefined): string {
	return hasText(name) ? `${name} (#${id})` : `#${id}`;
}
