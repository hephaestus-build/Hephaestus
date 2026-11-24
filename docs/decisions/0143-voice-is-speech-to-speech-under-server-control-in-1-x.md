# ADR 0143: Voice is speech-to-speech under the server's control, in 1.x

**Status:** Proposed
**Date:** 2026-11-23
**Authors:** Felix T.J. Dietrich

**Builds on:** [ADR 0083](0083-each-purpose-uses-one-governed-model-and-every-call-is-counted.md) (governed models and the AI choice), ADR 0145 (proposed in #4867, the Pi runtime interface)

**Relates to:** [ADR 0144](0144-a-call-stops-within-a-bound-or-its-route-stays-off-in-1-x.md) (the call transport and its stop bound)

> **1.0 does not build this.**
>
> - Speech-to-speech voice is the first item after 1.0. It is not on the critical path of 1.0 and not a freeze gate.
> - Dictation and read-aloud also come after 1.0, with voice. 1.0 ships no speech feature.
> - 1.0 reserves stable IDs and open enums only.
> - Voice adds its tables through additive migrations and its operations as additions (ADR 0135, proposed in #4867).

## Context

Some people think better when they talk.
A spoken conversation with Hephaestus should feel like a conversation with a person: full duplex, with barge-in and natural turn-taking.

Two architectures exist.
A chained pipeline runs speech-to-text, the text model and text-to-speech. The server sees every sentence as text before anyone hears it.
A speech-to-speech model hears the voice and speaks directly. It turns faster and sounds more natural, but no text exists before the audio.

The real constraint is to keep the server in control of what a call can read and do, while the model speaks directly.

## Decision drivers

- A call feels natural, with barge-in and turn-taking at human speed.
- The server decides the instructions, the context, the tools and the end of every call.
- A claim about a person's work comes from admitted evidence, not from the model's memory.
- No audio is stored, and no emotion is inferred.

## Considered options

1. **Chained voice for every deployment,** with a gate on each sentence before it is spoken. Every spoken word is checked, and the model never hears the voice. Turn-taking is slower and less natural, and barge-in needs extra work at each stage.
2. **Client-direct speech-to-speech with no server channel.** It is the fastest to build. The server cannot end the session, change its tools or see its transcript.
3. **A voice product of its own,** with phone numbers and SMS. Caller ID is not authentication, and a phone line is a second surface to secure.
4. **Speech-to-speech over WebRTC with a server sideband, a pluggable provider, and one read-only work tool (chosen).**

## Decision

Option 4. The rules below bind the 1.x design.

1. **Speech-to-speech over WebRTC.** The provider is pluggable. OpenAI Realtime is first. A chained adapter of streaming speech-to-text, the same text model and streaming text-to-speech is an allowed adapter.
2. **The server stays in control.** It mints a short-lived session secret and joins the session's sideband control channel. It supplies the instructions, the context and the tools, and it can end the session.
3. **The adapter has five verbs:** start, end, interruption, tool exchange and transcript events.
4. **One session reads one named workspace.** The authorization recheck builds the context. The context holds the conversation agent's instructions and a short slice of the conversation. It also holds the goals and preferences of that workspace.
5. **The context holds no raw work captures, no other workspace, no send rights and no credentials.**
6. **Claims about work come from one read-only tool.** The tool returns an admitted passage, or a short consult on a pinned observation, with its citation. On failure, Hephaestus says that it could not verify.
   - A verdict about work is spoken only on a transport that admits each claim before it is heard. On direct audio, the call gives no verdict about work and points to the item card.
7. **A call has no public or third-party effects.** The person saves or shares what was said on a screen.
8. **The person sees the processor and the transcript choice before the call starts.**
9. **The transcript comes from session events.** Barge-in truncates at the heard position, and interruptions are marked. "Not saved" discards the text at the end of the call. Audio never reaches Hephaestus storage.
10. **Emotion inference is off,** and provider affect features stay off. No identification by voice, no voice cloning, no phone number and no SMS.
11. **Limits:** one live call for each person, 30 minutes for each call, 60 minutes a day.

### What this changes in other ADRs

No ADR changes in 1.0. When voice ships, [ADR 0116](0116-a-push-is-one-web-push-message-from-the-instance.md) gets any new notification kind by an update under its rule 7.

## Consequences

- Good: a call sounds and turns like a conversation with a person.
- Good: the server sets every input of the call and can end it.
- Good: a verdict about work is spoken only where the server admitted it first.
- Bad: no text exists before the audio, so no admission runs on a sentence before it is heard. Only the work tool and the instructions bound what the model says.
- Bad: the provider hears the person's voice. The processor notice and the AI choice of [ADR 0083](0083-each-purpose-uses-one-governed-model-and-every-call-is-counted.md) must cover this.
- Bad: a self-hosted instance needs a speech-to-speech model or the chained adapter. At about 1,000 people, it needs one more GPU.

## Confirmation

Not enforced. 1.0 has no voice code.
Review catches a 1.0 change that adds a voice table, a voice operation or a reserved voice field.
When voice ships, tests fail when the call context holds a raw capture or another workspace, or when audio reaches storage.

## Revisit trigger

- Spot-checks find spoken claims about work without a citation.
- A speech-to-speech model that a self-hoster can run reaches the turn-taking of the hosted provider.

## Sources

- OpenAI, [Realtime API with WebRTC](https://developers.openai.com/api/docs/guides/voice-webrtc?api=realtime). A server mints an ephemeral client secret, and the browser connects with it. This gives decision 2.
- OpenAI, [Server-side controls](https://developers.openai.com/api/docs/guides/voice-server-controls?api=realtime). A server attaches a sideband connection to the call and keeps tools and instructions on the server. This gives decisions 2 and 6.
- EU AI Act, [Article 5](https://artificialintelligenceact.eu/article/5/). It forbids emotion recognition at the workplace, with narrow exceptions. This gives decision 10.
