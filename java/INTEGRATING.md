# Adding Observa to an existing backend

This guide is for a backend that already has a web or mobile app sending events with Observa's client SDK. It shows
what the server should add, and how to add it without changing the backend's behaviour. It was tried on a real Spring
Boot 4 admin backend (sign-in, team members, a catalogue); the steps are the same for any domain.

## 1. Send what only the server knows

The browser already tracks screens, clicks and the results it sees. The server must not send the same events again,
or every action is counted twice. It sends the facts the browser cannot know, or cannot be trusted with:

| Kind of fact | Example events | Why the browser can't send it |
|---|---|---|
| **Who the person is**, from your database | `$identify` with email, phone, name, role | In production a browser may not set email or phone: anyone could type someone else's |
| **Why something failed** | `account.sign_in_failed` with `reason: wrong_password / account_inactive` | The browser only sees a status code |
| **Before and after** | `product.price_changed` with `oldPrice`, `newPrice`, `changePercent` | Only the server has the old value |
| **Changes to other people** | `team.member_added`, `team.member_role_changed`, `team.member_deactivated` | The person affected is not the one at the keyboard |
| **What has no screen** | scheduled jobs, webhooks from partners, imports, scripts calling your API | No browser is involved at all |

## 2. Use the same user id as the app

The app calls `observa.identify(userId)` after sign-in. The server must use **that same id**, normally the user's
primary key in your database, as a string. Then server and browser events end up on one person, in one timeline.

To know who is acting in a request, read the signed-in user from your security context (for example the JWT's
subject → your user table → its id).

## 3. Add the starter and two settings

```xml
<!-- with the JitPack repository: https://jitpack.io -->
<dependency>
  <groupId>com.github.the1zozz.observa-sdks</groupId>
  <artifactId>observa-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

```properties
observa.key=${OBSERVA_KEY:}
observa.endpoint=${OBSERVA_ENDPOINT:https://observa.yourcompany.com}
```

With no key the SDK is off and the backend runs exactly as before: the existing tests keep passing without Observa.

The secret key can belong to the same source as the app's publishable key: one source, two keys.

## 4. Keep it in one class

Put every event the backend sends in one component, and call it from your services. Your business code stays
readable, and the list of events is in one place to review:

```java
@Component
public class AppEvents {
    private final Observa observa;
    public AppEvents(Observa observa) { this.observa = observa; }

    public void priceChanged(String actorId, Product before, BigDecimal newPrice) {
        afterCommit(() -> observa.track(actorId, "product.price_changed",
                Props.of("productId", before.getId(), "oldPrice", before.getPrice(), "newPrice", newPrice)));
    }
}
```

In the service, take the values you need *before* changing the entity, then call the component.

## 5. Send after the transaction commits

An event sent inside a transaction that is later rolled back reports something that never happened. Register the
send to run after commit:

```java
static void afterCommit(Runnable send) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { send.run(); }
        });
    } else {
        send.run();
    }
}
```

A failed sign-in is the exception: that is the event, so send it where the failure is caught, then rethrow the
exception so the backend answers as before.

## 6. What never goes in an event

- **Passwords, tokens and keys**, ever. Don't send the request body as it is.
- **The username someone typed at a failed sign-in**, when it matches no account. People sometimes type their password
  there. Record the failure on the person only when the account exists.
- **Whole database entities.** Pass the values you need. An entity's getters can load half the database.

## 7. Check it

1. Start the backend with `OBSERVA_KEY` and `observa.debug=true`. The log says `sending events to …`, then
   `connected` after the first event.
2. Do each action once: sign in, fail a sign-in, change a price, add a team member.
3. In the portal: **Data → Sources → your source → Live events** shows them as `SERVER`. **Customers → People → the
   person** shows them next to the same person's browser events.
4. In your own tests, use `ObservaTesting.capture()` to check that each action sends its event
   ([README](README.md#7-testing-your-own-code)).
