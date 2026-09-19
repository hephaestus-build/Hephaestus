const PLACEHOLDER_PREFIX = "WEB_ENV_";

export function sanitizeEnvironmentValue(value?: string | boolean): string {
	if (typeof value === "boolean") {
		return value ? "true" : "false";
	}
	const trimmed = value?.trim() ?? "";
	return trimmed.startsWith(PLACEHOLDER_PREFIX) ? "" : trimmed;
}

export function sanitizeEnvironmentBoolean(value?: string | boolean): boolean {
	return ["true", "1", "yes", "on"].includes(sanitizeEnvironmentValue(value).toLowerCase());
}
