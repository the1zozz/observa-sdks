# Java SDK examples

Two small programs to try the SDK against a running Observa. Both need a **secret key** of a source (Data → Sources →
your source → New key → Your servers) and Observa's address.

They use the published SDK from JitPack (version 0.1.0), so there is nothing to install first.

| Example | Shows | Run |
|---|---|---|
| [quickstart](quickstart) | Plain Java (8 or later): identify, an object as event data, a consent, and a mistake's warning | `OBSERVA_KEY=sk_… OBSERVA_ENDPOINT=http://localhost:8090 mvn -q compile exec:java` |
| [spring-boot](spring-boot) | Spring Boot 4 with the starter: inject `Observa`, track a record from a REST call | `OBSERVA_KEY=sk_… OBSERVA_ENDPOINT=http://localhost:8090 mvn -q spring-boot:run` |

Then look in the portal: **Data → Sources → your source → Live events**, and **Customers → People** for the user id.
