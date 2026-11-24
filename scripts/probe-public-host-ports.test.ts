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

function port(protocol: "tcp" | "udp", number: number, state = "open") {
	return `<port protocol="${protocol}" portid="${number}"><state state="${state}" reason="response"/></port>`;
}

void test("probes both address families and validates the optional availability marker", () => {
	const host = { ipv4: "192.0.2.3", ipv6: "2001:0db8::3" };
	assert.deepEqual(probeTargets([host]), [
		{ addresses: ["192.0.2.3", "2001:db8::3"], expectedOnline: true },
	]);
	assert.equal(probeTargets([{ ...host, expectedOnline: false }])[0]?.expectedOnline, false);
	assert.throws(() => probeTargets([]));
	assert.throws(() => probeTargets([{ ...host, expectedOnline: "false" }]));
	assert.throws(() => probeTargets([{ ...host, ipv6: "example.test" }]));
	assert.throws(() => probeTargets([host, { ...host, ipv4: "192.0.2.4", ipv6: "2001:db8::3" }]));
});

void test("fails on unexpected responses, including TCP resets and UDP port unreachable", () => {
	assert.deepEqual(
		assessScan(report([port("tcp", 80), port("tcp", 443, "closed"), port("udp", 443)]), true),
		{ reachable: true, hiddenReplies: false, unexpected: [] },
	);
	assert.deepEqual(
		assessScan(report([port("tcp", 10_250), port("udp", 8472, "closed"), port("udp", 80)]), true)
			.unexpected,
		["10250/tcp", "8472/udp", "80/udp"],
	);
	assert.deepEqual(
		assessScan(report([port("tcp", 443, "closed"), port("udp", 8472, "open|filtered")]), true)
			.unexpected,
		[],
	);
	assert.equal(assessScan(report([], "closed"), true).hiddenReplies, true);
});

void test("a complete silent scan passes only for a host marked down", () => {
	assert.throws(() => assessScan(report([], "open|filtered"), true));
	assert.deepEqual(assessScan(report([], "open|filtered"), false), {
		reachable: false,
		hiddenReplies: false,
		unexpected: [],
	});
	assert.deepEqual(assessScan(report([port("tcp", 22)]), false).unexpected, ["22/tcp"]);
});

void test("incomplete scans fail even for a host marked down", () => {
	assert.throws(() => assessScan(report().replace("<host>", '<host timedout="true">'), false));
	assert.throws(() => assessScan(report().replace('count="131070"', 'count="131069"'), false));
	assert.throws(() => assessScan(report().replace('exit="success"', 'exit="error"'), false));
	assert.throws(() =>
		assessScan(
			report().replace('protocol="tcp" numservices="65535"', 'protocol="tcp" numservices="1"'),
			false,
		),
	);
});
