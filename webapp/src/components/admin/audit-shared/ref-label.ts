import { firstNonBlank } from "@/lib/text";
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
