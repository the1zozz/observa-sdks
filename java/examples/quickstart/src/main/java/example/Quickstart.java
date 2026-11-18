package example;

import com.integralways.observa.sdk.Consent;
import com.integralways.observa.sdk.Observa;
import com.integralways.observa.sdk.Props;
import com.integralways.observa.sdk.Stats;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Sends a person, two events and a consent to Observa, then prints what happened.
 * The person's user id is the first argument (default: sdk-test-1).
 */
public class Quickstart {

    /** One of "your" objects: its getters become the event's data. */
    public static class Booking {
        private final String id;
        private final int nights;
        private final BigDecimal total;
        private final LocalDate checkIn;

        Booking(String id, int nights, BigDecimal total, LocalDate checkIn) {
            this.id = id;
            this.nights = nights;
            this.total = total;
            this.checkIn = checkIn;
        }

        public String getId() {
            return id;
        }

        public int getNights() {
            return nights;
        }

        public BigDecimal getTotal() {
            return total;
        }

        public LocalDate getCheckIn() {
            return checkIn;
        }
    }

    public static void main(String[] args) {
        String userId = args.length > 0 ? args[0] : "sdk-test-1";

        // Reads OBSERVA_KEY and OBSERVA_ENDPOINT. debug(true) logs each batch.
        Observa observa = Observa.builder().debug(true).build();
        if (!observa.isEnabled()) {
            System.err.println("Observa is off: see the log line above. Set OBSERVA_KEY and OBSERVA_ENDPOINT.");
            System.exit(1);
        }

        observa.identify(userId, Props.of("name", "SDK Test Person", "email", userId + "@example.com", "plan", "trial"));
        observa.track(userId, "booking.confirmed",
                new Booking("B-" + System.currentTimeMillis() % 10000, 3, new BigDecimal("450.00"), LocalDate.now().plusDays(14)));
        observa.track(userId, "booking.reminder_sent", Props.of("channel", "email", "daysBefore", 7));
        observa.consent(userId, Consent.email(true).text("Send me booking updates by email").form("quickstart"));

        // On purpose: a type Observa does not accept, to show the warning and its fix.
        observa.track(userId, "Booking Cancelled");

        boolean sent = observa.flush();
        Stats stats = observa.stats();
        observa.close();

        System.out.println();
        System.out.println(sent ? "Done." : "Not everything was sent yet: is Observa running at OBSERVA_ENDPOINT?");
        System.out.println("  sent " + stats.sent() + ", rejected " + stats.rejected() + ", retried " + stats.retried());
        System.out.println("  Now open Customers -> People and search for \"" + userId + "\".");
    }
}
