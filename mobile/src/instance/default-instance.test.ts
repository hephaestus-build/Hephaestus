import { describe, expect, it } from "vitest";

import {
	addressLabel,
	defaultInstanceAddress,
	type DefaultInstanceInputs,
} from "./default-instance";

const development: DefaultInstanceInputs = {
	development: true,
	configured: undefined,
	override: undefined,
	devServerPort: 18_380,
	isDevice: false,
	platform: "ios",
	metroHost: "192.168.1.20",
};

describe("defaultInstanceAddress", () => {
	it("offers a store build's configured Hephaestus", () => {
		expect(
			defaultInstanceAddress({
				...development,
				development: false,
				configured: "hephaestus.build",
			}),
		).toBe("hephaestus.build");
	});

	it("offers the worktree server on the iOS Simulator through localhost", () => {
		expect(defaultInstanceAddress(development)).toBe("http://localhost:18380");
	});

	it("offers it on the Android emulator through the host alias", () => {
		expect(defaultInstanceAddress({ ...development, platform: "android" })).toBe(
			"http://10.0.2.2:18380",
		);
	});

	it("offers it on a physical device at the address the device reaches Metro on", () => {
		expect(defaultInstanceAddress({ ...development, isDevice: true, platform: "android" })).toBe(
			"http://192.168.1.20:18380",
		);
	});

	it("offers nothing on a physical device that did not load its bundle from Metro", () => {
		expect(
			defaultInstanceAddress({ ...development, isDevice: true, metroHost: undefined }),
		).toBeUndefined();
	});

	it("lets an explicit address win", () => {
		expect(
			defaultInstanceAddress({ ...development, override: " https://staging.example.org " }),
		).toBe("https://staging.example.org");
	});
});

describe("addressLabel", () => {
	it("drops the scheme and a trailing slash", () => {
		expect(addressLabel("http://localhost:18380/")).toBe("localhost:18380");
		expect(addressLabel("hephaestus.build")).toBe("hephaestus.build");
	});
});
