/** Only a successful catalog probe permits forced tool choice; model names carry no capabilities. */
const forcedModels = new WeakSet<object>();

export function registerForcedToolChoice(model: object, supported: boolean): void {
	if (supported) {
		forcedModels.add(model);
	} else {
		forcedModels.delete(model);
	}
}

export function supportsForcedToolChoice(model: object | string | undefined): boolean {
	return typeof model === "object" && forcedModels.has(model);
}
