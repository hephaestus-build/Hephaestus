import assert from "node:assert/strict";
import { test } from "node:test";

import {
	conflictingSnapshot,
	liveDrift,
	renderPolicyMetrics,
	ruleChanges,
	rulesFor,
	savedRules,
	ufwArgs,
} from "./host-firewall.ts";

const base = {
	sshVpnCidrs: ["192.0.2.0/24", "2001:0DB8:1::/64"],
	monitoringAddresses: ["198.51.100.4", "2001:db8:2::4"],
};

const shared = [
	"allow from 192.0.2.0/24 to any port 22 proto tcp",
	"allow from 2001:db8:1::/64 to any port 22 proto tcp",
	"allow from 198.51.100.4/32 to any port 9100 proto tcp",
	"allow from 2001:db8:2::4/128 to any port 9100 proto tcp",
	"allow 80/tcp",
	"allow 443/tcp",
];

void test("k3s hosts allow only web, restricted services, and the k3s networks", () => {
	assert.deepEqual(
		rulesFor({ ...base, k3s: true, http3: false }).map((rule) => ufwArgs(rule).join(" ")),
		[
			...shared,
			"allow in on cni0",
			"allow in on flannel.1",
			"allow from 10.42.0.0/16",
			"allow from 10.43.0.0/16",
		],
	);
});

void test("a host proxy adds only 443/udp", () => {
	assert.deepEqual(
		rulesFor({ ...base, k3s: false, http3: true }).map((rule) => ufwArgs(rule).join(" ")),
		[...shared, "allow 443/udp"],
	);
});

void test("rejects malformed, broad, or incomplete address configuration", () => {
	const config = { ...base, k3s: false, http3: true };
	for (const invalid of [
		{ sshVpnCidrs: ["0.0.0.0/0", "2001:db8:1::/64"] },
		{ sshVpnCidrs: ["192.0.2.2/24", "2001:db8:1::/64"] },
		{ sshVpnCidrs: ["192.0.2.0/24", "2001:db8::/129"] },
		{ sshVpnCidrs: ["192.0.2.0/24"] },
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

function ufwFile(tuples: string[]) {
	return [
		"### RULES ###",
		...tuples.map((tuple) => `### tuple ### ${tuple}`),
		"### END RULES ###",
	].join("\n");
}

const ipv4 = [
	"allow tcp 22 0.0.0.0/0 any 192.0.2.0/24 in comment=56504e",
	"allow tcp 9100 0.0.0.0/0 any 198.51.100.4 in",
	"allow tcp 80 0.0.0.0/0 any 0.0.0.0/0 in",
	"allow tcp 443 0.0.0.0/0 any 0.0.0.0/0 in",
];
const ipv6 = [
	"allow tcp 22 ::/0 any 2001:db8:1::/64 in",
	"allow tcp 9100 ::/0 any 2001:db8:2::4 in",
	"allow tcp 80 ::/0 any ::/0 in",
	"allow tcp 443 ::/0 any ::/0 in",
];

void test("keeps matching saved rules, independent of order and comments", () => {
	const rules = rulesFor({ ...base, k3s: false, http3: false });
	assert.deepEqual(
		ruleChanges(rules, {
			4: savedRules(ufwFile(ipv4.toReversed()), 4),
			6: savedRules(ufwFile(ipv6), 6),
		}),
		{ add: [], remove: [] },
	);
});

void test("repairs a missing IPv6 rule and removes extra IPv4, IPv6, and routed rules by number", () => {
	const rules = rulesFor({ ...base, k3s: false, http3: false });
	const changes = ruleChanges(rules, {
		4: savedRules(ufwFile([...ipv4, "allow tcp 22 0.0.0.0/0 any 0.0.0.0/0 in"]), 4),
		6: savedRules(
			ufwFile([
				...ipv6.slice(0, -1),
				"allow udp 8472 ::/0 any ::/0 in",
				"route:allow any any ::/0 any ::/0 in_eth0!out_eth1",
			]),
			6,
		),
	});
	assert.deepEqual(changes.add.map(ufwArgs), [["allow", "443/tcp"]]);
	assert.deepEqual(changes.remove, [10, 9, 5]);
});

// Managed rules from Ubuntu UFW, with the private source addresses replaced.
const k3sV4 = [
	"-P INPUT DROP",
	"-P FORWARD DROP",
	"-P OUTPUT ACCEPT",
	"-A INPUT -j ufw-before-input",
	"-A FORWARD -j ufw-before-forward",
	"-A OUTPUT -j ufw-before-output",
	"-A ufw-before-input -j ufw-not-local",
	"-A ufw-before-input -j ufw-user-input",
	"-A ufw-before-forward -j ufw-user-forward",
	"-A ufw-before-output -j ufw-user-output",
	"-A ufw-user-input -s 192.0.2.0/24 -p tcp -m tcp --dport 22 -j ACCEPT",
	"-A ufw-user-input -s 198.51.100.4/32 -p tcp -m tcp --dport 9100 -j ACCEPT",
	"-A ufw-user-input -p tcp -m tcp --dport 80 -j ACCEPT",
	"-A ufw-user-input -p tcp -m tcp --dport 443 -j ACCEPT",
	"-A ufw-user-input -i cni0 -j ACCEPT",
	"-A ufw-user-input -i flannel.1 -j ACCEPT",
	"-A ufw-user-input -s 10.42.0.0/16 -j ACCEPT",
	"-A ufw-user-input -s 10.43.0.0/16 -j ACCEPT",
];
const k3sV6 = [
	"-P INPUT DROP",
	"-P FORWARD DROP",
	"-P OUTPUT ACCEPT",
	"-A INPUT -j ufw6-before-input",
	"-A FORWARD -j ufw6-before-forward",
	"-A OUTPUT -j ufw6-before-output",
	"-A ufw6-before-input -j ufw6-user-input",
	"-A ufw6-before-forward -j ufw6-user-forward",
	"-A ufw6-before-output -j ufw6-user-output",
	"-A ufw6-user-input -s 2001:db8:1::/64 -p tcp -m tcp --dport 22 -j ACCEPT",
	"-A ufw6-user-input -s 2001:db8:2::4/128 -p tcp -m tcp --dport 9100 -j ACCEPT",
	"-A ufw6-user-input -p tcp -m tcp --dport 80 -j ACCEPT",
	"-A ufw6-user-input -p tcp -m tcp --dport 443 -j ACCEPT",
	"-A ufw6-user-input -i cni0 -j ACCEPT",
	"-A ufw6-user-input -i flannel.1 -j ACCEPT",
];

void test("recognizes both live k3s rule sets and the saved interface tuples", () => {
	const rules = rulesFor({ ...base, k3s: true, http3: false });
	const tuples4 = [
		...ipv4,
		"allow any any 0.0.0.0/0 any 0.0.0.0/0 in_cni0",
		"allow any any 0.0.0.0/0 any 0.0.0.0/0 in_flannel.1",
		"allow any any 0.0.0.0/0 any 10.42.0.0/16 in",
		"allow any any 0.0.0.0/0 any 10.43.0.0/16 in",
	];
	const tuples6 = [
		...ipv6,
		"allow any any ::/0 any ::/0 in_cni0",
		"allow any any ::/0 any ::/0 in_flannel.1",
	];
	assert.deepEqual(
		ruleChanges(rules, {
			4: savedRules(ufwFile(tuples4), 4),
			6: savedRules(ufwFile(tuples6), 6),
		}),
		{ add: [], remove: [] },
	);
	assert.deepEqual(liveDrift(k3sV4, 4, rules), []);
	assert.deepEqual(liveDrift(k3sV6, 6, rules), []);
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
