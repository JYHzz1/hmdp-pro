package org.javaup.booking;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "BOOKING_TEST_MYSQL", matches = ".+")
class BookingMysqlTest {
    private static final String SCHEMA = "booking_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String MYSQL = System.getenv("BOOKING_TEST_MYSQL");
    private static final String USER = System.getenv().getOrDefault("BOOKING_TEST_USER", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("BOOKING_TEST_PASSWORD", "");
    private static BookingStore store;
    private BookingService bookings;

    @BeforeAll
    static void createSchema() throws Exception {
        assertTrue(MYSQL.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+"), "Use a local test server URL without a schema");
        schemaSql("CREATE DATABASE " + SCHEMA);
        store = new BookingStore(MYSQL + "/" + SCHEMA, USER, PASSWORD);
        Path ddl = Path.of("../sql/booking.sql");
        if (!Files.isRegularFile(ddl)) ddl = Path.of("sql/booking.sql");
        try (var connection = store.sql.getDataSource().getConnection()) {
            new ResourceDatabasePopulator(new FileSystemResource(ddl)).populate(connection);
        }
    }

    @AfterAll
    static void dropSchema() throws Exception {
        if (store != null) store.close();
        if (MYSQL.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+")) schemaSql("DROP DATABASE IF EXISTS " + SCHEMA);
    }

    private static void schemaSql(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(MYSQL + "/", USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @BeforeEach
    void seed() {
        bookings = new BookingService(store, null);
        for (String table : List.of("event", "command", "offer", "reservation", "entitlement",
                "slot", "staff", "product_shop", "shop", "product")) {
            store.sql.update("DELETE FROM booking_" + table);
        }
        store.sql.update("INSERT INTO booking_product VALUES (1,1,'demo',NOW()+INTERVAL 7 DAY)");
        store.sql.update("INSERT INTO booking_shop VALUES (1,1,'A',116.4,39.9),(2,1,'B',116.41,39.9)");
        store.sql.update("INSERT INTO booking_product_shop VALUES (1,1),(1,2)");
        store.sql.update("INSERT INTO booking_staff VALUES (99,1),(99,2)");
        store.sql.update("""
                INSERT INTO booking_slot(id,shop_id,voucher_id,start_at,end_at,capacity)
                VALUES (11,1,1,NOW()+INTERVAL 1 DAY,NOW()+INTERVAL 25 HOUR,1),
                       (12,2,1,NOW()+INTERVAL 1 DAY,NOW()+INTERVAL 25 HOUR,1)
                """);
        store.sql.update("INSERT INTO booking_entitlement(order_id,user_id,voucher_id) VALUES (21,31,1),(22,32,1)");
    }

    @Test
    void concurrentReplayReturnsSameResultAndOneMutation() throws Exception {
        var results = race(8, () -> bookings.reserve(31, 21, 11, "same"));
        for (var result : results) assertEquals(1L, result.get(10, TimeUnit.SECONDS));
        assertEquals(1, count("SELECT COUNT(*) FROM booking_event"));
        assertEquals(1, count("SELECT COUNT(*) FROM booking_command"));
        assertEquals(1, count("SELECT booked FROM booking_slot WHERE id=11"));
        assertEquals(BookingException.Code.REQUEST_CONFLICT,
                assertThrows(BookingException.class, () -> bookings.reserve(31, 21, 12, "same")).code);
    }

    @Test
    void separateEntitlementsCompeteForOneSlot() throws Exception {
        var results = race(List.of(() -> reserveResult(31, 21), () -> reserveResult(32, 22)));
        var actual = List.of(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS));
        assertTrue(actual.contains("OK"));
        assertTrue(actual.contains("SLOT_FULL"));
        assertEquals(1, count("SELECT COUNT(*) FROM booking_reservation"));
        assertEquals(1, count("SELECT booked FROM booking_slot WHERE id=11"));
    }

    @Test
    void expiryAndLateConfirmPreserveOriginalBooking() throws Exception {
        bookings.reserve(31, 21, 11, "reserve");
        long offer = bookings.propose(31, 21, 12, "offer");
        store.sql.update("UPDATE booking_offer SET expires_at=NOW()-INTERVAL 1 SECOND WHERE id=?", offer);
        var results = race(List.<Callable<String>>of(
                () -> { bookings.sweep(); return "SWEEP"; },
                () -> { try { bookings.confirm(31, 21, offer, "confirm"); return "OK"; }
                        catch (BookingException e) { return e.code.name(); } }));
        assertEquals("SWEEP", results.get(0).get(10, TimeUnit.SECONDS));
        assertNotEquals("OK", results.get(1).get(10, TimeUnit.SECONDS));
        assertEquals(11, count("SELECT slot_id FROM booking_reservation WHERE order_id=21"));
        assertEquals(0, count("SELECT held+booked FROM booking_slot WHERE id=12"));
        assertEquals(1, count("SELECT booked FROM booking_slot WHERE id=11"));
    }

    @Test
    void confirmationFailureRollsBackBothSlotCounters() {
        bookings.reserve(31, 21, 11, "reserve");
        long offer = bookings.propose(31, 21, 12, "offer");
        store.sql.execute("""
                CREATE TRIGGER reject_reschedule BEFORE UPDATE ON booking_reservation
                FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected update failure'
                """);
        try {
            assertThrows(DataAccessException.class, () -> bookings.confirm(31, 21, offer, "confirm"));
        } finally {
            store.sql.execute("DROP TRIGGER reject_reschedule");
        }
        assertEquals(1, count("SELECT booked FROM booking_slot WHERE id=11"));
        assertEquals(1, count("SELECT held FROM booking_slot WHERE id=12"));
        assertEquals(0, count("SELECT booked FROM booking_slot WHERE id=12"));
        assertEquals(0, count("SELECT COUNT(*) FROM booking_command WHERE operation='confirm'"));
        assertEquals(2, bookings.confirm(31, 21, offer, "confirm"));
    }

    @Test
    void competingConfirmAndCancelCannotBothChangeSameVersion() throws Exception {
        bookings.reserve(31, 21, 11, "reserve");
        long offer = bookings.propose(31, 21, 12, "offer");
        var results = race(List.<Callable<Boolean>>of(
                () -> attempt(() -> bookings.confirm(31, 21, offer, "confirm")),
                () -> attempt(() -> bookings.cancel(31, 21, 1, "cancel"))));
        boolean confirmed = results.get(0).get(10, TimeUnit.SECONDS);
        assertNotEquals(confirmed, results.get(1).get(10, TimeUnit.SECONDS));
        assertEquals(0, count("SELECT booked FROM booking_slot WHERE id=11"));
        assertEquals(0, count("SELECT held FROM booking_slot WHERE id=12"));
        assertEquals(confirmed ? 1 : 0, count("SELECT booked FROM booking_slot WHERE id=12"));
    }

    private boolean attempt(Runnable action) {
        try { action.run(); return true; }
        catch (BookingException rejected) { return false; }
    }

    private String reserveResult(long user, long order) {
        try { bookings.reserve(user, order, 11, "race"); return "OK"; }
        catch (BookingException rejected) { return rejected.code.name(); }
    }

    private int count(String sql) {
        return store.sql.queryForObject(sql, Integer.class);
    }

    private static <T> List<Future<T>> race(int count, Callable<T> action) throws Exception {
        return race(java.util.Collections.nCopies(count, action));
    }

    private static <T> List<Future<T>> race(List<Callable<T>> actions) throws Exception {
        var pool = Executors.newFixedThreadPool(actions.size());
        var barrier = new CyclicBarrier(actions.size());
        try {
            var simultaneous = actions.stream().map(action -> (Callable<T>) () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return action.call();
            }).toList();
            return pool.invokeAll(simultaneous, 15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
