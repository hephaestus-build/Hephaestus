// Dependency-free: CI jobs without an install read the pin through kubernetes-cluster.ts.

/** The gVisor release that k3s-containerd.toml.tmpl pins, and the SHA-512 of its archive per architecture. */
export function gvisorPin(template: string): {
	release: string;
	sha512: { x64: string; arm64: string };
} {
	const pin =
		/^# pin: gvisor (?<release>\d{8}\.\d+) (?<x64>[a-f0-9]{128}) (?<arm64>[a-f0-9]{128})$/mu.exec(
			template,
		)?.groups;
	if (pin?.release === undefined || pin.x64 === undefined || pin.arm64 === undefined) {
		throw new Error("k3s-containerd.toml.tmpl has no gVisor pin");
	}
	return { release: pin.release, sha512: { x64: pin.x64, arm64: pin.arm64 } };
}
