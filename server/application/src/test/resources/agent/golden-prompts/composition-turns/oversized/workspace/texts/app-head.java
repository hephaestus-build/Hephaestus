package acme;

import java.io.IOException;

final class App {
    private final Client client;

    App(Client client) {
        this.client = client;
    }

    String fetch(String id) {
        try {
            return client.get(id);
        } catch (IOException e) {
            return null;
        }
    }
}
