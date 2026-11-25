import assert from "node:assert/strict";
import { test } from "node:test";

import { assessScan, probeTargets } from "./probe-public-host-ports.ts";

function report(ports: string[] = [], aggregateState = "filtered") {
	return `<?xml version="1.0"?>
<nmaprun scanner="nmap">
<scaninfo type="syn" protocol="tcp" numservices="65535" services="1-65535"/>
<scaninfo type="udp" protocol="udp" numservices="65535" services="1-65535"/>
<host>
<ports><extraports state="${aggregateState}" count="${131_070 - ports.length}">
</extraports>
${ports.join("\n")}
</ports>
</host>
<runstats><finished time="1" exit="success"/><hosts up="1" down="0" total="1"/></runstats>
</nmaprun>`;
}

function port(protocol: "tcp" | "udp", number: number, state = "open", reason = "response") {
	return `<port protocol="${protocol}" portid="${number}"><state state="${state}" reason="${reason}"/></port>`;
}

const online = { expectedOnline: true, http3: false };
const down = { ...online, expectedOnline: false };

void test("probes both address families and validates optional host settings", () => {
	const host = { ipv4: "192.0.2.3", ipv6: "2001:0db8::3" };
	assert.deepEqual(probeTargets([host]), [{ addresses: ["192.0.2.3", "2001:db8::3"], ...online }]);
	assert.equal(probeTargets([{ ...host, expectedOnline: false }])[0]?.expectedOnline, false);
	assert.equal(probeTargets([{ ...host, http3: true }])[0]?.http3, true);
	assert.throws(() => probeTargets([]));
	assert.throws(() => probeTargets([{ ...host, expectedOnline: "false" }]));
	assert.throws(() => probeTargets([{ ...host, http3: "false" }]));
	assert.throws(() => probeTargets([{ ...host, ipv6: "example.test" }]));
	assert.throws(() => probeTargets([host, { ...host, ipv4: "192.0.2.4", ipv6: "2001:db8::3" }]));
});

void test("fails on unexpected responses, including TCP resets and UDP port unreachable", () => {
	assert.deepEqual(assessScan(report([port("tcp", 80), port("tcp", 443, "closed")]), online), {
		reachable: true,
		hiddenReplies: false,
		unexpected: [],
	});
	assert.deepEqual(
		assessScan(report([port("tcp", 10_250), port("udp", 8472, "closed"), port("udp", 80)]), online)
			.unexpected,
		["10250/tcp", "8472/udp", "80/udp"],
	);
	assert.deepEqual(
		assessScan(report([port("tcp", 443, "closed"), port("udp", 8472, "open|filtered")]), online)
			.unexpected,
		[],
	);
	assert.equal(assessScan(report([], "closed"), online).hiddenReplies, true);
});

void test("accepts UDP 443 only for an HTTP/3 target", () => {
	const scan = report([port("tcp", 443), port("udp", 443)]);
	assert.deepEqual(assessScan(scan, online).unexpected, ["443/udp"]);
	assert.deepEqual(assessScan(scan, down).unexpected, ["443/udp"]);
	assert.deepEqual(assessScan(scan, { ...online, http3: true }).unexpected, []);
});

void test("ICMP prohibition is a reply even when Nmap reports the port as filtered", () => {
	const denied = port("udp", 8472, "filtered", "admin-prohibited");
	const silent = port("tcp", 6443, "filtered", "no-response");
	const deniedV6 = port("tcp", 10_250, "filtered", "icmp6-adm-prohibited");
	assert.deepEqual(assessScan(report([denied, silent, deniedV6]), down).unexpected, [
		"8472/udp",
		"10250/tcp",
	]);
	const grouped = report([]).replace(
		"</extraports>",
		'<extrareasons reason="no-response" count="131069"/><extrareasons reason="admin-prohibited" count="1"/></extraports>',
	);
	assert.equal(assessScan(grouped, down).hiddenReplies, true);
	assert.equal(
		assessScan(
			report([]).replace(
				"</extraports>",
				'<extrareasons reason="no-response" count="131070"/></extraports>',
			),
			down,
		).hiddenReplies,
		false,
	);
});

void test("a complete silent scan passes only for a host marked down", () => {
	assert.throws(() => assessScan(report([], "open|filtered"), online));
	assert.deepEqual(assessScan(report([], "open|filtered"), down), {
		reachable: false,
		hiddenReplies: false,
		unexpected: [],
	});
	assert.deepEqual(assessScan(report([port("tcp", 22)]), down).unexpected, ["22/tcp"]);
});

void test("incomplete scans fail even for a host marked down", () => {
	assert.throws(() => assessScan(report().replace("<host>", '<host timedout="true">'), down));
	assert.throws(() => assessScan(report().replace('count="131070"', 'count="131069"'), down));
	assert.throws(() => assessScan(report().replace('exit="success"', 'exit="error"'), down));
	assert.throws(() =>
		assessScan(
			report().replace('protocol="tcp" numservices="65535"', 'protocol="tcp" numservices="1"'),
			down,
		),
	);
});
