package org.javaup.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.javaup.dto.Result;
import org.javaup.utils.UserHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/booking")
@ConditionalOnProperty("hmdp.booking.jdbc-url")
class BookingController {
    private final BookingService bookings;

    BookingController(BookingService bookings) {
        this.bookings = bookings;
    }

    record Command(@Positive long value, @NotBlank @Size(max = 64) String requestId) {}
    record Redeem(@Positive long orderId, @Positive long version, @NotBlank @Size(max = 64) String requestId) {}
    record PlanOptions(@Min(1) @Max(50) int radiusKm, @Min(1) @Max(168) int windowHours) {}
    record Preference(@Min(1) @Max(50) int radiusKm, @Min(1) @Max(168) int windowHours) {}

    @GetMapping("/supported/{voucherId}")
    Result<Boolean> supported(@PathVariable long voucherId) {
        return Result.ok(bookings.manages(voucherId));
    }

    @PostMapping("/entitlements/{voucherId}")
    Result<BookingService.BookingView> open(@PathVariable long voucherId) {
        return Result.ok(bookings.open(user(), voucherId));
    }

    @GetMapping("/{orderId}")
    Result<BookingService.BookingView> view(@PathVariable long orderId) {
        return Result.ok(bookings.view(user(), orderId));
    }

    @PostMapping("/{orderId}/reserve")
    Result<Long> reserve(@PathVariable long orderId, @Valid @RequestBody Command command) {
        return Result.ok(bookings.reserve(user(), orderId, command.value(), command.requestId()));
    }

    @PostMapping("/{orderId}/offers")
    Result<Long> propose(@PathVariable long orderId, @Valid @RequestBody Command command) {
        return Result.ok(bookings.propose(user(), orderId, command.value(), command.requestId()));
    }

    @PostMapping("/{orderId}/confirm")
    Result<Long> confirm(@PathVariable long orderId, @Valid @RequestBody Command command) {
        return Result.ok(bookings.confirm(user(), orderId, command.value(), command.requestId()));
    }

    @PostMapping("/{orderId}/decline")
    Result<Long> decline(@PathVariable long orderId, @Valid @RequestBody Command command) {
        return Result.ok(bookings.decline(user(), orderId, command.value(), command.requestId()));
    }

    @PostMapping("/{orderId}/cancel")
    Result<Long> cancel(@PathVariable long orderId, @Valid @RequestBody Command command) {
        return Result.ok(bookings.cancel(user(), orderId, command.value(), command.requestId()));
    }

    @PutMapping("/{orderId}/preference")
    Result<Void> preference(@PathVariable long orderId, @Valid @RequestBody Preference preference) {
        bookings.preference(user(), orderId, preference.radiusKm(), preference.windowHours());
        return Result.ok();
    }

    @PostMapping("/shops/{shopId}/redeem")
    Result<Long> redeem(@PathVariable long shopId, @Valid @RequestBody Redeem command) {
        return Result.ok(bookings.redeem(user(), shopId, command.orderId(), command.version(), command.requestId()));
    }

    @PostMapping("/slots/{slotId}/close")
    Result<Void> close(@PathVariable long slotId) {
        bookings.closeSlot(user(), slotId);
        return Result.ok();
    }

    @PostMapping("/slots/{slotId}/compare")
    Result<BookingService.Comparison> compare(@PathVariable long slotId, @Valid @RequestBody PlanOptions options) {
        return Result.ok(bookings.compare(user(), slotId, options.radiusKm(), options.windowHours()));
    }

    @PostMapping("/slots/{slotId}/assign")
    Result<Map<String, Object>> assign(@PathVariable long slotId, @Valid @RequestBody PlanOptions options) {
        return Result.ok(bookings.applyPlan(user(), slotId, options.radiusKm(), options.windowHours()));
    }

    @ExceptionHandler(BookingException.class)
    Result<Map<String, String>> rejected(BookingException error) {
        Result<Map<String, String>> response = Result.fail(error.getMessage());
        response.setData(Map.of("code", error.code.name()));
        return response;
    }

    private long user() {
        return UserHolder.getUser().getId();
    }
}
