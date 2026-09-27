/**
 * The Hephaestus the welcome screen offers first.
 *
 * Store and preview builds offer the one named in their configuration. A development build offers the
 * server of the worktree it was built in, at an address the device it runs on can reach: the iOS
 * Simulator shares the host's localhost; the Android emulator reaches the host as 10.0.2.2; a physical
 * device reaches it at the address it reaches Metro on. An explicit address overrides all of this.
 */
export interface DefaultInstanceInputs {
	development: boolean;
	/** The configured default of a store or preview build. */
	configured: string | undefined;
	/** `EXPO_PUBLIC_DEV_SERVER_URL`, for a development build that should use another server. */
	override: string | undefined;
	/** The worktree server's port, read from its environment when the build was configured. */
	devServerPort: number | undefined;
	isDevice: boolean;
	platform: "ios" | "android";
	/** The host a physical device reaches Metro on, when the bundle came from Metro. */
	metroHost: string | undefined;
}

export function defaultInstanceAddress(inputs: DefaultInstanceInputs): string | undefined {
	if (!inputs.development) {
		return inputs.configured;
	}
	const override = inputs.override?.trim();
	if (override !== undefined && override !== "") {
		return override;
	}
	if (inputs.devServerPort === undefined) {
		return undefined;
	}
	let host: string | undefined;
	if (inputs.isDevice) {
		host = inputs.metroHost;
	} else {
		host = inputs.platform === "android" ? "10.0.2.2" : "localhost";
	}
	return host === undefined ? undefined : `http://${host}:${inputs.devServerPort}`;
}

/** How an address reads on a button: its host, without the scheme a person never typed. */
export function addressLabel(address: string): string {
	return address.replace(/^[a-z]+:\/\//iu, "").replace(/\/+$/u, "");
}
