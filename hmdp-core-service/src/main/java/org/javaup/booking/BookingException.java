package org.javaup.booking;

class BookingException extends RuntimeException {
    enum Code { INVALID_STATE, SLOT_FULL, STALE_VERSION, FORBIDDEN, REQUEST_CONFLICT, OFFER_EXPIRED }

    final Code code;

    BookingException(String message) {
        this(Code.INVALID_STATE, message);
    }

    BookingException(Code code, String message) {
        super(message);
        this.code = code;
    }
}
