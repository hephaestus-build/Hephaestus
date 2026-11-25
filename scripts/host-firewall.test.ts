import assert from "node:assert/strict";
import { test } from "node:test";

import {
	conflictingSnapshot,
	liveDrift,
	renderPolicyMetrics,
	rulesFor,
	savedDrift,
	settingsDrift,
} from "./host-firewall.ts";

const base = {
	sshVpnCidrs: ["192.0.2.0/24", "2001:0DB8:1::/64"],
	monitoringAddresses: ["198.51.100.4", "2001:db8:2::4"],
};

const shared: ReturnType<typeof rulesFor> = [
	{ family: 4, source: "192.0.2.0/24", port: 22, protocol: "tcp" },
	{ family: 6, source: "2001:db8:1::/64", port: 22, protocol: "tcp" },
	{ family: 4, source: "198.51.100.4/32", port: 9100, protocol: "tcp" },
	{ family: 6, source: "2001:db8:2::4/128", port: 9100, protocol: "tcp" },
	{ port: 80, protocol: "tcp" },
	{ port: 443, protocol: "tcp" },
];

void test("k3s hosts allow cluster traffic only on the two k3s interfaces", () => {
	assert.deepEqual(rulesFor({ ...base, k3s: true, http3: false }), [
		...shared,
		{ device: "cni0" },
		{ device: "flannel.1" },
	]);
});

void test("a host proxy adds only 443/udp", () => {
	assert.deepEqual(rulesFor({ ...base, k3s: false, http3: true }), [
		...shared,
		{ port: 443, protocol: "udp" },
	]);
});

void test("rejects malformed addresses, default routes and missing VPN networks", () => {
	const config = { ...base, k3s: false, http3: true };
	for (const invalid of [
		{ sshVpnCidrs: ["0.0.0.0/0", "2001:db8:1::/64"] },
		{ sshVpnCidrs: ["192.0.2.2/24", "2001:db8:1::/64"] },
		{ sshVpnCidrs: ["192.0.2.0/24", "2001:db8::/129"] },
		{ sshVpnCidrs: [] },
		{ monitoringAddresses: ["198.51.100.4/24", "2001:db8:2::4"] },
		{ monitoringAddresses: ["198.51.100.4", "::ffff:192.0.2.4"] },
		{ monitoringAddresses: ["198.51.100.4", "fe80::4%eth0"] },
		{ monitoringAddresses: ["198.51.100.4", "2001:db8:2::4", "2001:db8:2::5"] },
		{ http3: "false" },
		{ extra: true },
	]) {
		assert.throws(() => rulesFor({ ...config, ...invalid }));
	}
});

void test("an address family with no VPN or monitoring source gets only the web rules", () => {
	assert.deepEqual(
		rulesFor({ sshVpnCidrs: ["192.0.2.0/24"], monitoringAddresses: [], k3s: false, http3: false }),
		[
			{ family: 4, source: "192.0.2.0/24", port: 22, protocol: "tcp" },
			{ port: 80, protocol: "tcp" },
			{ port: 443, protocol: "tcp" },
		],
	);
});

const k3sV4 = [
	"-P INPUT DROP",
	"-P FORWARD DROP",
	"-P OUTPUT ACCEPT",
	'-A INPUT -m comment --comment "KUBE-ROUTER rule to explicitly ACCEPT traffic that comply to network policies" -m mark --mark 0x20000/0x20000 -j ACCEPT',
	"-A INPUT -j ufw-before-logging-input",
	"-A INPUT -j ufw-before-input",
	"-A INPUT -j ufw-after-input",
	"-A INPUT -j ufw-after-logging-input",
	"-A INPUT -j ufw-reject-input",
	"-A INPUT -j ufw-track-input",
	"-A FORWARD -j ufw-before-forward",
	"-A OUTPUT -j ufw-before-output",
	"-A ufw-before-input -j ufw-not-local",
	"-A ufw-before-input -j ufw-user-input",
	"-A ufw-before-forward -j ufw-user-forward",
	"-A ufw-before-output -j ufw-user-output",
	'-A ufw-logging-deny -m limit --limit 3/min --limit-burst 10 -j LOG --log-prefix "[UFW BLOCK] "',
	"-A ufw-not-local -m addrtype --dst-type LOCAL -j RETURN",
	"-A ufw-not-local -m addrtype --dst-type MULTICAST -j RETURN",
	"-A ufw-not-local -m addrtype --dst-type BROADCAST -j RETURN",
	"-A ufw-not-local -m limit --limit 3/min --limit-burst 10 -j ufw-logging-deny",
	"-A ufw-not-local -j DROP",
	"-A ufw-skip-to-policy-input -j DROP",
	"-A ufw-user-limit -j REJECT --reject-with icmp-port-unreachable",
	"-A ufw-user-limit-accept -j ACCEPT",
	"-A ufw-user-input -s 192.0.2.0/24 -p tcp -m tcp --dport 22 -j ACCEPT",
	"-A ufw-user-input -s 198.51.100.4/32 -p tcp -m tcp --dport 9100 -j ACCEPT",
	"-A ufw-user-input -p tcp -m tcp --dport 80 -j ACCEPT",
	"-A ufw-user-input -p tcp -m tcp --dport 443 -j ACCEPT",
	"-A ufw-user-input -i cni0 -j ACCEPT",
	"-A ufw-user-input -i flannel.1 -j ACCEPT",
];
const k3sV6 = [
	"-P INPUT DROP",
	"-P FORWARD DROP",
	"-P OUTPUT ACCEPT",
	"-A INPUT -i lo -j ACCEPT",
	"-A INPUT -j ufw6-before-logging-input",
	"-A INPUT -j ufw6-before-input",
	"-A INPUT -j ufw6-after-input",
	"-A INPUT -j ufw6-after-logging-input",
	"-A INPUT -j ufw6-reject-input",
	"-A INPUT -j ufw6-track-input",
	"-A FORWARD -j ufw6-before-forward",
	"-A OUTPUT -j ufw6-before-output",
	"-A ufw6-before-input -m rt --rt-type 0 -j DROP",
	"-A ufw6-before-input -j ufw6-user-input",
	"-A ufw6-before-forward -j ufw6-user-forward",
	"-A ufw6-before-output -j ufw6-user-output",
	'-A ufw6-logging-deny -m limit --limit 3/min --limit-burst 10 -j LOG --log-prefix "[UFW BLOCK] "',
	"-A ufw6-skip-to-policy-input -j DROP",
	"-A ufw6-user-limit -j REJECT --reject-with icmp6-port-unreachable",
	"-A ufw6-user-limit-accept -j ACCEPT",
	"-A ufw6-user-input -s 2001:db8:1::/64 -p tcp -m tcp --dport 22 -j ACCEPT",
	"-A ufw6-user-input -s 2001:db8:2::4/128 -p tcp -m tcp --dport 9100 -j ACCEPT",
	"-A ufw6-user-input -p tcp -m tcp --dport 80 -j ACCEPT",
	"-A ufw6-user-input -p tcp -m tcp --dport 443 -j ACCEPT",
	"-A ufw6-user-input -i cni0 -j ACCEPT",
	"-A ufw6-user-input -i flannel.1 -j ACCEPT",
];

void test("the check reports unmanaged input rules and jumps around the UFW user rules", () => {
	const rules = rulesFor({ ...base, k3s: true, http3: false });
	for (const [family, lines, exception] of [
		[4, k3sV4, "-A INPUT -p tcp -m tcp --dport 6443 -j ACCEPT"],
		[4, k3sV4, "-A INPUT -m mark --mark 0x10000/0x10000 -j ACCEPT"],
		[4, k3sV4, '-A INPUT -m comment --comment "local rules" -j CUSTOM-INPUT'],
		[6, k3sV6, "-A ufw6-before-input -p udp -m udp --dport 8472 -j ACCEPT"],
		[6, k3sV6, "-A ufw6-after-input -p udp -m udp --dport 8472 -j ACCEPT"],
		[4, k3sV4, "-A ufw-before-logging-input -p tcp -m tcp --dport 6443 -j ACCEPT"],
		[4, k3sV4, "-A ufw-reject-input -p tcp -m tcp --dport 6443 -j ACCEPT"],
		[6, k3sV6, "-A ufw6-track-input -p udp -m udp --dport 8472 -j ACCEPT"],
	] as const) {
		assert.deepEqual(liveDrift([...lines, exception], family, rules), [
			`IPv${family} has an unmanaged input rule.`,
		]);
	}
	assert.deepEqual(
		liveDrift(
			[...k3sV4, "-A ufw-before-input -p udp -m udp --sport 67 --dport 68 -j ACCEPT"],
			4,
			rules,
		),
		[],
	);
});

void test("reports altered UFW input helper chains", () => {
	const rules = rulesFor({ ...base, k3s: true, http3: false });
	for (const [family, lines, rule] of [
		[4, k3sV4, "-A ufw-not-local -p tcp -m tcp --dport 443 -j DROP"],
		[4, k3sV4, "-A ufw-not-local -j ACCEPT"],
		[4, k3sV4, "-A ufw-logging-deny -j DROP"],
		[4, k3sV4, "-A ufw-skip-to-policy-input -j ACCEPT"],
		[4, k3sV4, "-A ufw-user-limit -j ACCEPT"],
		[6, k3sV6, "-A ufw6-user-limit-accept -p tcp -m tcp --dport 443 -j REJECT"],
	] as const) {
		assert.deepEqual(liveDrift([rule, ...lines], family, rules), [
			`IPv${family} has an unmanaged input rule.`,
		]);
	}
	assert.deepEqual(
		liveDrift(
			k3sV4.filter((line) => !line.includes("--dst-type LOCAL -j RETURN")),
			4,
			rules,
		),
		["IPv4 has an unmanaged input rule."],
	);
});

void test("the check reports disabled IPv6 even when the other UFW settings match", () => {
	const expected = [
		"DEFAULT_INPUT_POLICY=DROP",
		"DEFAULT_OUTPUT_POLICY=ACCEPT",
		"DEFAULT_FORWARD_POLICY=DROP",
	].join("\n");
	assert.deepEqual(
		settingsDrift(`IPV6=no\n${expected}`, "ENABLED=yes", "enabled").map(({ reason }) => reason),
		["ipv6_disabled"],
	);
	assert.deepEqual(settingsDrift(`IPV6=yes\n${expected}`, "ENABLED=yes", "enabled"), []);
});

const savedK3sV4 = [
	"-A ufw-user-input -p tcp --dport 22 -s 192.0.2.0/24 -j ACCEPT",
	"-A ufw-user-input -p tcp --dport 9100 -s 198.51.100.4 -j ACCEPT",
	"-A ufw-user-input -p tcp --dport 80 -j ACCEPT",
	"-A ufw-user-input -p tcp --dport 443 -j ACCEPT",
	"-A ufw-user-input -i cni0 -j ACCEPT",
	"-A ufw-user-input -i flannel.1 -j ACCEPT",
];

void test("recognizes both live k3s rule sets and checks saved filter rules per family", () => {
	const rules = rulesFor({ ...base, k3s: true, http3: false });
	assert.equal(
		savedDrift(["# UFW metadata", ...savedK3sV4.toReversed()].join("\n"), 4, rules),
		false,
	);
	assert.equal(savedDrift(savedK3sV4.slice(1).join("\n"), 4, rules), true);
	assert.deepEqual(liveDrift(k3sV4, 4, rules), []);
	assert.deepEqual(liveDrift(k3sV6, 6, rules), []);
});

void test("reports unrestricted Pod and Service source rules as drift", () => {
	const rules = rulesFor({ ...base, k3s: true, http3: false });
	for (const network of ["10.42.0.0/16", "10.43.0.0/16"]) {
		const unscoped = `-A ufw-user-input -s ${network} -j ACCEPT`;
		assert.deepEqual(liveDrift([...k3sV4, unscoped], 4, rules), [
			"IPv4 live UFW input rules differ.",
		]);
		assert.equal(savedDrift([...savedK3sV4, unscoped].join("\n"), 4, rules), true);
	}
});

void test("reports denies before required input rules and allows stock invalid-packet drops", () => {
	const rules = rulesFor({ ...base, k3s: true, http3: false });
	const builtin = "-A INPUT -p tcp -m tcp --dport 443 -j DROP";
	const before = "-A ufw6-before-input -p tcp -m tcp --dport 22 -j REJECT";
	for (const deny of [builtin, before]) {
		assert.deepEqual(liveDrift([deny, ...k3sV6], 6, rules), [
			"IPv6 has an input deny before the required UFW rules.",
		]);
		assert.deepEqual(liveDrift([...k3sV6, deny], 6, rules), []);
	}
	assert.deepEqual(
		liveDrift(["-A ufw6-before-input -m conntrack --ctstate INVALID -j DROP", ...k3sV6], 6, rules),
		[],
	);
	for (const family of [4, 6] as const) {
		const lines = family === 4 ? k3sV4 : k3sV6;
		const chain = family === 4 ? "ufw-before-logging-input" : "ufw6-before-logging-input";
		assert.deepEqual(
			liveDrift([...lines, `-A ${chain} -p tcp -m tcp --dport 443 -j DROP`], family, rules),
			[`IPv${family} has an input deny before the required UFW rules.`],
		);
		assert.deepEqual(liveDrift([...lines, `-A ${chain} -j RETURN`], family, rules), []);
	}
});

void test("preserves scoped ingress denies for private platform ports", () => {
	const rules = rulesFor({ ...base, k3s: true, http3: false });
	const denies = [
		'-A INPUT -i eth0 -p udp -m udp --dport 8472 -m comment --comment "protect overlay" -j DROP',
		"-A INPUT -i eth0 -p tcp -m multiport --dports 6443,10250 -j DROP",
	];
	assert.deepEqual(liveDrift([...denies, ...k3sV4], 4, rules), []);
	assert.deepEqual(liveDrift([...denies, ...k3sV6], 6, rules), []);
	for (const conflicting of [
		"-A INPUT -i eth0 -p tcp -m multiport --dports 6443,443 -j DROP",
		"-A INPUT -i eth0 -p tcp -m tcp --dport 9100 -j REJECT",
		"-A INPUT -i cni0 -p tcp -m tcp --dport 6443 -j DROP",
		"-A INPUT -i flannel.1 -p udp -m udp --dport 8472 -j DROP",
	]) {
		assert.deepEqual(liveDrift([conflicting, ...k3sV4], 4, rules), [
			"IPv4 has an input deny before the required UFW rules.",
		]);
	}
});

void test("detects changed defaults, missing live hooks, and extra live rules", () => {
	const rules = rulesFor({ ...base, k3s: true, http3: false });
	const changed = k3sV6.filter(
		(line) =>
			!line.includes("-P INPUT") &&
			!line.includes("before-forward -j") &&
			!line.includes("-i cni0"),
	);
	assert.deepEqual(
		liveDrift(
			[
				...changed,
				"-P INPUT ACCEPT",
				"-A ufw6-user-output -j DROP",
				"-A ufw6-user-forward -j ACCEPT",
			],
			6,
			rules,
		),
		[
			"IPv6 UFW INPUT chain or policy differs.",
			"IPv6 UFW OUTPUT has extra rules.",
			"IPv6 UFW FORWARD chain or policy differs.",
			"IPv6 UFW FORWARD has extra rules.",
			"IPv6 live UFW input rules differ.",
		],
	);
});

void test("an enabled persistence service cannot share ownership with UFW", () => {
	assert.equal(conflictingSnapshot("enabled", true, false), true);
	assert.equal(conflictingSnapshot("enabled-runtime", false, true), true);
	assert.equal(conflictingSnapshot("enabled", false, false), false);
	assert.equal(conflictingSnapshot("disabled", true, true), false);
	assert.equal(conflictingSnapshot("", false, false), false);
});

void test("reports the verdict, a bounded set of drift reasons, and the check time", () => {
	const metrics = renderPolicyMetrics(["live_rules", "netfilter_persistent", "live_rules"], 123);
	assert.match(metrics, /^host_firewall_policy_ok 0$/mu);
	assert.match(metrics, /^host_firewall_policy_check_timestamp_seconds 123$/mu);
	assert.deepEqual(
		metrics.split("\n").filter((line) => line.startsWith("host_firewall_policy_drift{")),
		[
			'host_firewall_policy_drift{reason="live_rules"} 1',
			'host_firewall_policy_drift{reason="netfilter_persistent"} 1',
		],
	);
	assert.match(renderPolicyMetrics([], 124), /^host_firewall_policy_ok 1$/mu);
	assert.doesNotMatch(renderPolicyMetrics([], 124), /^host_firewall_policy_drift\{/mu);
});
