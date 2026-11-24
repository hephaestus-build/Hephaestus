# ADR 0144: A call stops within a stated bound when access ends, or its route stays off, in 1.x

**Status:** Proposed
**Date:** 2026-11-23
**Authors:** Felix T.J. Dietrich

**Builds on:** [ADR 0143](0143-voice-is-speech-to-speech-under-server-control-in-1-x.md) (speech-to-speech voice)

**Relates to:** [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) (one server role)

> **1.0 does not build this.**
>
> - The call transport ships with voice, after 1.0.
> - 1.0 reserves stable IDs and open enums only.
> - 1.0 creates no media relay, no transport value and no call table.

## Context

A call is a live link between a person's device and a speech provider.
Access can end during a call.
A workspace admin removes the person, the person pauses Hephaestus, or an instance admin turns voice off.
Then the call must end quickly, because the provider still holds context from that workspace.

With speech-to-speech over WebRTC ([ADR 0143](0143-voice-is-speech-to-speech-under-server-control-in-1-x.md)), the media flows between the device and the provider.
The server ends a call through its sideband channel.
In a network partition, the server cannot reach the provider, and the provider's session lives until its own limit.
That limit is often many minutes.

The real constraint is a stop bound that holds also in a partition.

## Decision drivers

- Access loss ends the call within a stated bound.
- The bound holds when the server cannot reach the provider.
- A self-hoster does not run a media server unless the provider needs one.

## Considered options

1. **Trust the provider's session limit.** A partition lets a call run for minutes after access ended.
2. **A media relay for every call.** The server can always cut media. Every self-hoster then runs and secures a media server, also when the provider can bound the stop.
3. **An own media server or a WebSocket relay of audio.** It needs media and network engineering that a maintained open-source server already does.
4. **A tiered transport (chosen).** Direct media with a sideband stop where the provider bounds the stop. Else a media relay. Else the route stays off.

## Decision

Option 4. The rules below bind the 1.x design.

1. **Access loss ends the call.** The server revokes the session, closes provider media through the sideband and cancels late tool calls.
2. **The stop has a stated bound.** The target is 1 second from the access change to the end of speech, measured across two server replicas.
3. **A provider route qualifies for direct media only if the provider bounds the stop in a partition.** A partition test on that provider decides. A short lifetime of the session secret is not enough, because the secret bounds only the start of a session.
4. **Else the call uses a media relay** that the server controls. The relay cuts the media when the server ends the call or loses its lease on it.
5. **Else the route stays off.** Voice is not offered on that provider.
6. **The relay is LiveKit** when one is needed, self-hosted under Apache-2.0. The admin guide documents a fully in-house setup.
7. **Turning voice off is an instance switch.** It ends every live call within the same bound.

### What this changes in other ADRs

No ADR changes. [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) gets no new runtime role: a media relay, when used, is an optional chart component, not a role of the JAR.

## Consequences

- Good: a person who loses access cannot keep talking with Hephaestus about that workspace.
- Good: a provider that bounds the stop needs no relay.
- Bad: a provider without a bound needs a relay, which is one more component to run and secure.
- Bad: some providers stay off for voice.
- Bad: a relay adds a network hop to every call that uses it.

## Confirmation

Not enforced. 1.0 has no voice code.
When voice ships, a revocation latency test runs with two server replicas. It fails when an access change on one replica does not end a call on the other within 1 second.
A partition test fails when a direct-media route keeps media past the bound.

## Revisit trigger

- A provider adds a server-side kill that holds in a partition.
- The relay causes more than 1 % of calls to fail to connect.

## Sources

- OpenAI, [Server-side controls](https://developers.openai.com/api/docs/guides/voice-server-controls?api=realtime). The server ends a call through a sideband connection. That stop works only while the server reaches the provider. This gives decisions 3 to 5.
- LiveKit, [livekit/livekit](https://github.com/livekit/livekit). An open-source WebRTC media server under Apache-2.0. This gives decision 6.
