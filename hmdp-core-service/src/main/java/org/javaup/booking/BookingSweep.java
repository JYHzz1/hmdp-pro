package org.javaup.booking;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty("hmdp.booking.jdbc-url")
class BookingSweep {
    private final BookingService bookings;

    BookingSweep(BookingService bookings) {
        this.bookings = bookings;
    }

    @Scheduled(fixedDelayString = "${hmdp.booking.sweep-ms:5000}")
    void expire() {
        bookings.sweep();
    }
}
