package example;

import com.integralways.observa.sdk.Observa;
import com.integralways.observa.sdk.Props;

import java.util.Map;
import java.util.UUID;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class BookingApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookingApplication.class, args);
    }

    record BookingRequest(String userId, int nights) {
    }

    record Booking(String id, int nights, String status) {
    }

    @RestController
    static class BookingController {

        private final Observa observa;   // from the starter

        BookingController(Observa observa) {
            this.observa = observa;
        }

        @PostMapping("/bookings")
        ResponseEntity<?> book(@RequestBody BookingRequest request) {
            if (request.nights() < 1 || request.nights() > 30) {
                observa.track(request.userId(), "booking.refused", Props.of("reason", "nights", "nights", request.nights()));
                return ResponseEntity.badRequest().body(Map.of("error", "nights must be 1 to 30"));
            }
            Booking booking = new Booking("B-" + UUID.randomUUID().toString().substring(0, 8), request.nights(), "confirmed");
            observa.track(request.userId(), "booking.confirmed", booking);   // the record's fields are the data
            return ResponseEntity.ok(booking);
        }
    }
}
