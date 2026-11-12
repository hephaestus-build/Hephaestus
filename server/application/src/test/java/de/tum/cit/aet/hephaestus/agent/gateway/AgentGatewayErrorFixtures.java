package de.tum.cit.aet.hephaestus.agent.gateway;

/** Supplies the real gateway advice to isolated controller tests without making it a public production API. */
public final class AgentGatewayErrorFixtures {
    private AgentGatewayErrorFixtures() {}

    public static Object errors() {
        return new AgentGatewayErrors();
    }
}
