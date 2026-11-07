package de.tum.cit.aet.hephaestus.agent.gateway;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

/**
 * Hands the gateway's filters the address of the TCP peer. {@code getRemoteAddr()} cannot serve there: a forwarded
 * header rewrites it for a peer that the trusted proxies match, and those often match the whole Pod network, sandboxes
 * included. Only a valve can read the peer: the servlet API has no call for it.
 */
final class SandboxGatewayPeerValve extends ValveBase {

    SandboxGatewayPeerValve() {
        super(true);
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
        request.setAttribute(SandboxGatewayConnector.PEER_ATTRIBUTE, request.getPeerAddr());
        getNext().invoke(request, response);
    }
}
