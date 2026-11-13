CREATE TABLE IF NOT EXISTS booking_product (
    voucher_id BIGINT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    service_name VARCHAR(100) NOT NULL,
    valid_until DATETIME(6) NOT NULL
);
CREATE TABLE IF NOT EXISTS booking_shop (
    id BIGINT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    longitude DECIMAL(10,7) NOT NULL,
    latitude DECIMAL(10,7) NOT NULL
);
CREATE TABLE IF NOT EXISTS booking_product_shop (
    voucher_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    PRIMARY KEY (voucher_id, shop_id)
);
CREATE TABLE IF NOT EXISTS booking_staff (
    user_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, shop_id)
);
CREATE TABLE IF NOT EXISTS booking_slot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    voucher_id BIGINT NOT NULL,
    start_at DATETIME(6) NOT NULL,
    end_at DATETIME(6) NOT NULL,
    capacity INT NOT NULL,
    held INT NOT NULL DEFAULT 0,
    booked INT NOT NULL DEFAULT 0,
    open BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE KEY uk_slot (shop_id, voucher_id, start_at),
    KEY ix_candidates (voucher_id, open, start_at),
    CHECK (held >= 0 AND booked >= 0 AND held + booked <= capacity)
);
CREATE TABLE IF NOT EXISTS booking_entitlement (
    order_id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    voucher_id BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
    radius_km INT,
    window_hours INT,
    KEY ix_owner (user_id, order_id)
);
CREATE TABLE IF NOT EXISTS booking_reservation (
    order_id BIGINT PRIMARY KEY,
    slot_id BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL,
    KEY ix_affected (slot_id, state, order_id)
);
CREATE TABLE IF NOT EXISTS booking_offer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    target_slot_id BIGINT NOT NULL,
    reservation_version BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    state VARCHAR(16) NOT NULL,
    KEY ix_due (state, expires_at, id),
    KEY ix_offer_order (order_id, state)
);
CREATE TABLE IF NOT EXISTS booking_command (
    order_id BIGINT NOT NULL,
    operation VARCHAR(20) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    arguments VARCHAR(128) NOT NULL,
    response_value BIGINT NOT NULL,
    PRIMARY KEY (order_id, operation, request_id)
);
CREATE TABLE IF NOT EXISTS booking_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    operation VARCHAR(24) NOT NULL,
    from_slot_id BIGINT,
    to_slot_id BIGINT,
    version BIGINT NOT NULL,
    happened_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY ix_event_order (order_id, id)
);
