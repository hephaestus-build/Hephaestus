/**
 * A person-readable name for a signed-in session from the user agent the server recorded: this app
 * names itself (`Hephaestus/<version> (<OS> <version>)`); anything else is a browser.
 */
export function sessionLabel(userAgent: string | undefined, nativeApp: boolean): string {
	const platform =
		userAgent === undefined
			? undefined
			: /^Hephaestus\/[\d.]+ \((?<os>iOS|Android)\b/u.exec(userAgent)?.groups?.os;
	if (platform !== undefined) {
		return `Hephaestus app on ${platform === "iOS" ? "iPhone or iPad" : "Android"}`;
	}
	if (nativeApp) {
		return "Hephaestus app";
	}
	if (userAgent === undefined || userAgent === "") {
		return "Unknown device";
	}
	// Most specific first: Edge also says Chrome, and Chrome also says Safari.
	const name = [
		["Edg/", "Edge"],
		["Firefox/", "Firefox"],
		["Chrome/", "Chrome"],
		["Safari/", "Safari"],
	].find(([marker]) => marker !== undefined && userAgent.includes(marker))?.[1];
	return name === undefined ? "Web browser" : `${name} in a browser`;
}
