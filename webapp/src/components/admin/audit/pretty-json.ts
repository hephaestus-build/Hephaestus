import { hasText } from "@/lib/text";

export function prettyJson(value: string | undefined): string | null {
	if (!hasText(value)) {
		return null;
	}
	try {
		return JSON.stringify(JSON.parse(value), null, 2);
	} catch {
		return value;
	}
}
