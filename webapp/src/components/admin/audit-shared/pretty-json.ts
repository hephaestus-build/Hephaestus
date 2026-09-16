export function prettyJson(value: string | undefined): string | null {
	if (!value) {
		return null;
	}
	try {
		return JSON.stringify(JSON.parse(value), null, 2);
	} catch {
		return value;
	}
}
