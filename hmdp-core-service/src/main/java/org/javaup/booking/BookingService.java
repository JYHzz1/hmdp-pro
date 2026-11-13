package org.javaup.booking;

import org.javaup.entity.VoucherOrder;
import org.javaup.enums.OrderStatus;
import org.javaup.service.IVoucherOrderService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

import static org.javaup.booking.BookingException.Code.*;

@Service
@ConditionalOnProperty("hmdp.booking.jdbc-url")
public class BookingService {
    private final BookingStore store;
    private final JdbcTemplate sql;
    private final IVoucherOrderService orders;

    BookingService(BookingStore store, IVoucherOrderService orders) {
        this.store = store;
        this.sql = store.sql;
        this.orders = orders;
    }

    record Entitlement(long orderId, long userId, long voucherId, String state, LocalDateTime validUntil,
                       Integer radiusKm, Integer windowHours) {}
    record Reservation(long slotId, String state, long version) {}
    record Slot(long id, long shopId, long voucherId, String shop, LocalDateTime start,
                LocalDateTime end, int capacity, int held, int booked, boolean open,
                double latitude, double longitude) {}
    record Offer(long id, long target, long version, LocalDateTime expires, String state) {}
    record BookingView(long orderId, long voucherId, String entitlementState, Reservation reservation,
                       Slot currentSlot, Offer offer, Slot offeredSlot, List<Slot> candidates) {}
    record Comparison(AssignmentPlanner.Plan nearest, AssignmentPlanner.Plan constrained,
                      AssignmentPlanner.Plan maximum) {}

    public boolean manages(long voucherId) {
        return count("SELECT COUNT(*) FROM booking_product WHERE voucher_id=?", voucherId) > 0;
    }

    BookingView open(long user, long voucherId) {
        require(manages(voucherId), "该券未开通门店预约");
        VoucherOrder order = orders.lambdaQuery().eq(VoucherOrder::getUserId, user)
                .eq(VoucherOrder::getVoucherId, voucherId)
                .eq(VoucherOrder::getStatus, OrderStatus.NORMAL.getCode()).one();
        require(order != null, "请先领取此券并等待订单到账");
        sql.update("""
                INSERT INTO booking_entitlement (order_id,user_id,voucher_id) VALUES (?,?,?)
                ON DUPLICATE KEY UPDATE order_id=order_id
                """, order.getId(), user, voucherId);
        return view(user, order.getId());
    }

    BookingView view(long user, long orderId) {
        return store.tx.execute(status -> {
            Entitlement entitlement = owned(user, orderId);
            Reservation reservation = reservation(orderId);
            Offer offer = pending(orderId);
            LocalDateTime now = now();
            Slot current = reservation == null ? null : slot(reservation.slotId(), false);
            Slot offered = offer == null ? null : slot(offer.target(), false);
            return new BookingView(orderId, entitlement.voucherId(), entitlement.state(), reservation,
                    current, offer, offered, candidates(entitlement.voucherId(), now, entitlement.validUntil()));
        });
    }

    long reserve(long user, long orderId, long slotId, String requestId) {
        return store.tx.execute(status -> {
            Entitlement entitlement = owned(user, orderId);
            return command(orderId, "reserve", requestId, Long.toString(slotId), () -> {
                require(entitlement.state().equals("AVAILABLE"), "该券已有预约或已使用");
                Reservation previous = reservation(orderId);
                Slot target = slot(slotId, true);
                LocalDateTime now = now();
                validateTarget(entitlement, target, now);
                require(sql.update("""
                        UPDATE booking_slot SET booked=booked+1
                        WHERE id=? AND held+booked<capacity
                        """, slotId) == 1, SLOT_FULL, "时段已满");
                long version = previous == null ? 1 : previous.version() + 1;
                sql.update("""
                        INSERT INTO booking_reservation(order_id,slot_id,state,version)
                        VALUES (?,?,'CONFIRMED',?)
                        ON DUPLICATE KEY UPDATE slot_id=?,state='CONFIRMED',version=?
                        """, orderId, slotId, version, slotId, version);
                state(orderId, "RESERVED");
                event(orderId, user, "RESERVE", null, slotId, version);
                return version;
            });
        });
    }

    long propose(long user, long orderId, long targetId, String requestId) {
        return store.tx.execute(status -> {
            Entitlement entitlement = owned(user, orderId);
            return command(orderId, "propose", requestId, Long.toString(targetId),
                    () -> createOffer(entitlement, targetId, user));
        });
    }

    private long createOffer(Entitlement entitlement, long targetId, long actor) {
        long orderId = entitlement.orderId();
        Reservation reservation = confirmed(orderId);
        require(reservation.slotId() != targetId, "请选择新的时段");
        require(pending(orderId) == null, "请先处理现有改期方案");
        Slots slots = lockSlots(reservation.slotId(), targetId);
        LocalDateTime now = now();
        LocalDateTime cutoff = slots.original().start().minusMinutes(30);
        validateTarget(entitlement, slots.target(), now);
        require(now.isBefore(cutoff), "已过改期截止时间");
        require(sql.update("""
                UPDATE booking_slot SET held=held+1 WHERE id=? AND held+booked<capacity
                """, targetId) == 1, SLOT_FULL, "目标时段已满，原预约仍有效");
        LocalDateTime expires = now.plusMinutes(5);
        if (cutoff.isBefore(expires)) expires = cutoff;
        if (slots.target().start().isBefore(expires)) expires = slots.target().start();
        LocalDateTime deadline = expires;
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        sql.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO booking_offer(order_id,target_slot_id,reservation_version,expires_at,state)
                    VALUES (?,?,?,?,'PENDING')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, orderId);
            statement.setLong(2, targetId);
            statement.setLong(3, reservation.version());
            statement.setObject(4, deadline);
            return statement;
        }, key);
        event(orderId, actor, "OFFER", reservation.slotId(), targetId, reservation.version());
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    long confirm(long user, long orderId, long offerId, String requestId) {
        return store.tx.execute(status -> {
            Entitlement entitlement = owned(user, orderId);
            return command(orderId, "confirm", requestId, Long.toString(offerId), () -> {
                Reservation reservation = confirmed(orderId);
                Offer offer = activeOffer(orderId, offerId);
                require(offer.version() == reservation.version(), STALE_VERSION, "预约已变更，请刷新");
                Slots slots = lockSlots(reservation.slotId(), offer.target());
                LocalDateTime now = now();
                require(now.isBefore(offer.expires()), OFFER_EXPIRED, "改期方案已过期");
                validateTarget(entitlement, slots.target(), now);
                require(sql.update("UPDATE booking_slot SET held=held-1,booked=booked+1 WHERE id=? AND held>0",
                        offer.target()) == 1, "目标占位不存在");
                releaseBooked(reservation.slotId());
                long version = reservation.version() + 1;
                sql.update("UPDATE booking_reservation SET slot_id=?,version=? WHERE order_id=?",
                        offer.target(), version, orderId);
                sql.update("UPDATE booking_offer SET state='ACCEPTED' WHERE id=?", offerId);
                event(orderId, user, "RESCHEDULE", reservation.slotId(), offer.target(), version);
                return version;
            });
        });
    }

    long decline(long user, long orderId, long offerId, String requestId) {
        return store.tx.execute(status -> {
            owned(user, orderId);
            return command(orderId, "decline", requestId, Long.toString(offerId), () -> {
                Offer offer = activeOffer(orderId, offerId);
                slot(offer.target(), true);
                releaseOffer(orderId, offer, user, "DECLINED");
                return offerId;
            });
        });
    }

    long cancel(long user, long orderId, long version, String requestId) {
        return store.tx.execute(status -> {
            owned(user, orderId);
            return command(orderId, "cancel", requestId, Long.toString(version), () -> {
                Reservation reservation = confirmed(orderId);
                require(reservation.version() == version, STALE_VERSION, "预约已变更，请刷新");
                Offer offer = pending(orderId);
                Slot current = lockSlots(reservation.slotId(), offer == null ? reservation.slotId() : offer.target()).original();
                require(now().isBefore(current.start().minusMinutes(30)) || !current.open(), "已过取消截止时间");
                if (offer != null) releaseOffer(orderId, offer, user, "DECLINED");
                releaseBooked(current.id());
                state(orderId, "AVAILABLE");
                return finish(orderId, reservation, user, "CANCELLED");
            });
        });
    }

    long redeem(long actor, long shopId, long orderId, long version, String requestId) {
        return store.tx.execute(status -> {
            staff(actor, shopId);
            lockEntitlement(orderId);
            return command(orderId, "redeem", requestId, shopId + ":" + version, () -> {
                Reservation reservation = confirmed(orderId);
                require(reservation.version() == version, STALE_VERSION, "预约已变更，请刷新");
                Offer offer = pending(orderId);
                Slot current = lockSlots(reservation.slotId(), offer == null ? reservation.slotId() : offer.target()).original();
                require(current.shopId() == shopId && current.open(), "该预约不可在此门店核销");
                LocalDateTime now = now();
                require(!now.isBefore(current.start().minusMinutes(15)) && now.isBefore(current.end()), "未在核销时间内");
                if (offer != null) releaseOffer(orderId, offer, actor, "DECLINED");
                state(orderId, "USED");
                return finish(orderId, reservation, actor, "COMPLETED");
            });
        });
    }

    void closeSlot(long actor, long slotId) {
        store.tx.executeWithoutResult(status -> {
            Slot current = slot(slotId, true);
            staff(actor, current.shopId());
            sql.update("UPDATE booking_slot SET open=FALSE WHERE id=?", slotId);
        });
    }

    void preference(long user, long orderId, int radiusKm, int windowHours) {
        store.tx.executeWithoutResult(status -> {
            owned(user, orderId);
            require(pending(orderId) == null, "请先处理现有改期方案");
            sql.update("UPDATE booking_entitlement SET radius_km=?,window_hours=? WHERE order_id=?",
                    radiusKm, windowHours, orderId);
        });
    }

    Comparison compare(long actor, long slotId, int radiusKm, int windowHours) {
        PlanningInput input = planningInput(actor, slotId, radiusKm, windowHours);
        return new Comparison(AssignmentPlanner.plan(input.demands(), input.capacity(), false),
                AssignmentPlanner.plan(input.demands(), input.capacity(), true),
                AssignmentPlanner.maximum(input.demands(), input.capacity()));
    }

    private record PlanningInput(List<AssignmentPlanner.Demand> demands, Map<Long, Integer> capacity) {
        AssignmentPlanner.Plan constrained() {
            return AssignmentPlanner.plan(demands, capacity, true);
        }
    }

    private PlanningInput planningInput(long actor, long slotId, int radiusKm, int windowHours) {
        return store.snapshot.execute(status -> {
            Slot source = slot(slotId, false);
            staff(actor, source.shopId());
            require(!source.open(), "请先关闭受影响时段");
            LocalDateTime now = now();
            List<Long> orders = sql.queryForList("""
                    SELECT order_id FROM booking_reservation
                    WHERE slot_id=? AND state='CONFIRMED' ORDER BY order_id
                    """, Long.class, slotId);
            LocalDateTime windowStart = source.start().minusHours(windowHours);
            List<Slot> available = candidates(source.voucherId(), windowStart.isAfter(now) ? windowStart : now,
                    source.start().plusHours(windowHours).plusHours(1));
            Map<Long, Integer> capacity = new HashMap<>();
            List<AssignmentPlanner.Demand> demands = new ArrayList<>();
            for (long orderId : orders) {
                Entitlement entitlement = entitlement(orderId, false);
                List<AssignmentPlanner.Candidate> choices = new ArrayList<>();
                if (!now.isBefore(source.start().minusMinutes(30)) || pending(orderId) != null
                        || entitlement.radiusKm() == null || entitlement.windowHours() == null) {
                    demands.add(new AssignmentPlanner.Demand(orderId, List.of()));
                    continue;
                }
                int hours = Math.min(windowHours, entitlement.windowHours());
                for (Slot candidate : available) {
                    if (candidate.shopId() == source.shopId() || candidate.start().isBefore(source.start().minusHours(hours))
                            || candidate.start().isAfter(source.start().plusHours(hours))
                            || candidate.end().isAfter(entitlement.validUntil())) continue;
                    double distance = distance(source, candidate);
                    if (distance <= Math.min(radiusKm, entitlement.radiusKm())) {
                        capacity.put(candidate.id(), candidate.capacity() - candidate.held() - candidate.booked());
                        choices.add(new AssignmentPlanner.Candidate(candidate.id(), distance));
                    }
                }
                demands.add(new AssignmentPlanner.Demand(orderId, List.copyOf(choices)));
            }
            return new PlanningInput(demands, capacity);
        });
    }

    Map<String, Object> applyPlan(long actor, long closedSlot, int radiusKm, int windowHours) {
        var plan = planningInput(actor, closedSlot, radiusKm, windowHours).constrained();
        List<Long> offers = new ArrayList<>();
        Map<String, String> conflicts = new HashMap<>();
        int planned = plan.matches().size();
        sendOffers(actor, closedSlot, radiusKm, windowHours, plan, offers, conflicts);
        if (!conflicts.isEmpty()) {
            var refreshed = planningInput(actor, closedSlot, radiusKm, windowHours).constrained();
            sendOffers(actor, closedSlot, radiusKm, windowHours, refreshed, offers, conflicts);
        }
        return Map.of("planned", planned, "offers", offers, "conflicts", conflicts);
    }

    private void sendOffers(long actor, long closedSlot, int radiusKm, int windowHours,
                            AssignmentPlanner.Plan plan, List<Long> offers, Map<String, String> conflicts) {
        for (var match : plan.matches()) {
            String order = Long.toString(match.orderId());
            try {
                Long id = store.tx.execute(status -> {
                    Entitlement entitlement = lockEntitlement(match.orderId());
                    require(confirmed(match.orderId()).slotId() == closedSlot, "预约已变更");
                    Slot original = slot(closedSlot, false);
                    Slot target = slot(match.slotId(), false);
                    require(entitlement.radiusKm() != null && entitlement.windowHours() != null,
                            "用户改派偏好已变化");
                    int hours = Math.min(windowHours, entitlement.windowHours());
                    require(distance(original, target) <= Math.min(radiusKm, entitlement.radiusKm())
                            && !target.start().isBefore(original.start().minusHours(hours))
                            && !target.start().isAfter(original.start().plusHours(hours)), "用户改派偏好已变化");
                    return createOffer(entitlement, match.slotId(), actor);
                });
                offers.add(id);
                conflicts.remove(order);
            } catch (BookingException e) {
                conflicts.put(order, e.getMessage());
            }
        }
    }

    void sweep() {
        List<Map<String, Object>> expired = sql.queryForList("""
                SELECT id,order_id FROM booking_offer
                WHERE state='PENDING' AND expires_at<=NOW(6) ORDER BY expires_at,id LIMIT 100
                """);
        for (var row : expired) {
            long orderId = ((Number) row.get("order_id")).longValue();
            long offerId = ((Number) row.get("id")).longValue();
            store.tx.executeWithoutResult(status -> {
                lockEntitlement(orderId);
                Offer offer = pending(orderId);
                if (offer != null && offer.id() == offerId) {
                    slot(offer.target(), true);
                    if (!now().isBefore(offer.expires())) releaseOffer(orderId, offer, 0, "EXPIRED");
                }
            });
        }
        List<Long> ended = sql.queryForList("""
                SELECT r.order_id FROM booking_reservation r JOIN booking_slot s ON s.id=r.slot_id
                WHERE r.state='CONFIRMED' AND s.end_at<=NOW(6) ORDER BY s.end_at,r.order_id LIMIT 100
                """, Long.class);
        for (long orderId : ended) {
            store.tx.executeWithoutResult(status -> {
                lockEntitlement(orderId);
                Reservation reservation = reservation(orderId);
                if (reservation == null || !reservation.state().equals("CONFIRMED")) return;
                Offer offer = pending(orderId);
                Slot current = lockSlots(reservation.slotId(), offer == null ? reservation.slotId() : offer.target()).original();
                if (now().isBefore(current.end())) return;
                if (offer != null) releaseOffer(orderId, offer, 0, "DECLINED");
                if (current.open()) {
                    state(orderId, "USED");
                    finish(orderId, reservation, 0, "NO_SHOW");
                } else {
                    releaseBooked(current.id());
                    state(orderId, "AVAILABLE");
                    finish(orderId, reservation, 0, "CANCELLED");
                }
            });
        }
    }

    private long finish(long orderId, Reservation reservation, long actor, String state) {
        long version = reservation.version() + 1;
        sql.update("UPDATE booking_reservation SET state=?,version=? WHERE order_id=?", state, version, orderId);
        event(orderId, actor, state, reservation.slotId(), null, version);
        return version;
    }

    private long command(long orderId, String operation, String requestId, String arguments, LongSupplier action) {
        require(requestId != null && !requestId.isBlank() && requestId.length() <= 64, "缺少有效的请求编号");
        var saved = sql.queryForList("""
                SELECT arguments,response_value FROM booking_command WHERE order_id=? AND operation=? AND request_id=?
                """, orderId, operation, requestId);
        if (!saved.isEmpty()) {
            require(saved.get(0).get("arguments").equals(arguments), REQUEST_CONFLICT, "请求编号已用于其他参数");
            return ((Number) saved.get(0).get("response_value")).longValue();
        }
        long result = action.getAsLong();
        sql.update("""
                INSERT INTO booking_command (order_id,operation,request_id,arguments,response_value)
                VALUES (?,?,?,?,?)
                """, orderId, operation, requestId, arguments, result);
        return result;
    }

    private Entitlement owned(long user, long orderId) {
        Entitlement entitlement = lockEntitlement(orderId);
        require(entitlement.userId() == user, FORBIDDEN, "该预约不属于当前用户");
        return entitlement;
    }

    private Entitlement lockEntitlement(long orderId) {
        return entitlement(orderId, true);
    }

    private Entitlement entitlement(long orderId, boolean lock) {
        if (lock) {
            require(!sql.queryForList("SELECT order_id FROM booking_entitlement WHERE order_id=? FOR UPDATE",
                    Long.class, orderId).isEmpty(), "预约权益不存在");
        }
        var rows = sql.query("""
                SELECT e.*,p.valid_until FROM booking_entitlement e JOIN booking_product p ON p.voucher_id=e.voucher_id
                WHERE e.order_id=?
                """, (r, i) -> new Entitlement(r.getLong("order_id"), r.getLong("user_id"), r.getLong("voucher_id"),
                r.getString("state"), r.getObject("valid_until", LocalDateTime.class),
                r.getObject("radius_km", Integer.class), r.getObject("window_hours", Integer.class)), orderId);
        require(!rows.isEmpty(), "预约权益不存在");
        return rows.get(0);
    }

    private static final String SLOT_SELECT = """
            SELECT s.*,h.name,h.latitude,h.longitude FROM booking_slot s JOIN booking_shop h ON h.id=s.shop_id
            """;

    private Slot slot(long id, boolean lock) {
        if (lock) {
            require(!sql.queryForList("SELECT id FROM booking_slot WHERE id=? FOR UPDATE",
                    Long.class, id).isEmpty(), "时段不存在");
        }
        var rows = sql.query(SLOT_SELECT + " WHERE s.id=?", BookingService::readSlot, id);
        require(!rows.isEmpty(), "时段不存在");
        return rows.get(0);
    }

    private List<Slot> candidates(long voucherId, LocalDateTime earliest, LocalDateTime latest) {
        return sql.query(SLOT_SELECT + """
                JOIN booking_product_shop ps ON ps.shop_id=s.shop_id AND ps.voucher_id=s.voucher_id
                JOIN booking_product p ON p.voucher_id=s.voucher_id AND p.merchant_id=h.merchant_id
                WHERE s.voucher_id=? AND s.open AND s.start_at>? AND s.end_at<=?
                  AND s.held+s.booked<s.capacity ORDER BY s.start_at,s.id LIMIT 300
                """, BookingService::readSlot, voucherId, earliest, latest);
    }

    private static Slot readSlot(java.sql.ResultSet r, int ignored) throws java.sql.SQLException {
        return new Slot(r.getLong("id"), r.getLong("shop_id"), r.getLong("voucher_id"), r.getString("name"),
                r.getObject("start_at", LocalDateTime.class), r.getObject("end_at", LocalDateTime.class),
                r.getInt("capacity"), r.getInt("held"), r.getInt("booked"), r.getBoolean("open"),
                r.getDouble("latitude"), r.getDouble("longitude"));
    }

    private record Slots(Slot original, Slot target) {}

    private Slots lockSlots(long original, long target) {
        Slot first = slot(Math.min(original, target), true);
        Slot second = original == target ? first : slot(Math.max(original, target), true);
        return original <= target ? new Slots(first, second) : new Slots(second, first);
    }

    private void validateTarget(Entitlement entitlement, Slot target, LocalDateTime now) {
        require(target.voucherId() == entitlement.voucherId() && target.open() && now.isBefore(target.start())
                && !target.end().isAfter(entitlement.validUntil()), "该券不可预约此时段");
        require(count("""
                SELECT COUNT(*) FROM booking_product_shop ps JOIN booking_shop h ON h.id=ps.shop_id
                JOIN booking_product p ON p.voucher_id=ps.voucher_id AND p.merchant_id=h.merchant_id
                WHERE ps.voucher_id=? AND ps.shop_id=?
                """, entitlement.voucherId(), target.shopId()) == 1, "该券不可在此门店使用");
    }

    private Reservation reservation(long orderId) {
        return sql.query("""
                SELECT slot_id,state,version FROM booking_reservation WHERE order_id=?
                """, (r, i) -> new Reservation(r.getLong(1), r.getString(2), r.getLong(3)), orderId)
                .stream().findFirst().orElse(null);
    }

    private Reservation confirmed(long orderId) {
        Reservation reservation = reservation(orderId);
        require(reservation != null && reservation.state().equals("CONFIRMED"), "预约已结束或不存在");
        return reservation;
    }

    private Offer pending(long orderId) {
        return sql.query("""
                SELECT id,target_slot_id,reservation_version,expires_at,state FROM booking_offer
                WHERE order_id=? AND state='PENDING'
                """, (r, i) -> new Offer(r.getLong(1), r.getLong(2), r.getLong(3),
                r.getObject(4, LocalDateTime.class), r.getString(5)), orderId).stream().findFirst().orElse(null);
    }

    private Offer activeOffer(long orderId, long offerId) {
        Offer offer = pending(orderId);
        require(offer != null && offer.id() == offerId, STALE_VERSION, "改期方案已失效");
        return offer;
    }

    private void releaseOffer(long orderId, Offer offer, long actor, String state) {
        require(sql.update("UPDATE booking_slot SET held=held-1 WHERE id=? AND held>0", offer.target()) == 1,
                "占位记录异常");
        sql.update("UPDATE booking_offer SET state=? WHERE id=?", state, offer.id());
        event(orderId, actor, state, null, offer.target(), offer.version());
    }

    private void releaseBooked(long slotId) {
        require(sql.update("UPDATE booking_slot SET booked=booked-1 WHERE id=? AND booked>0", slotId) == 1,
                "预约容量记录异常");
    }

    private void state(long orderId, String state) {
        sql.update("UPDATE booking_entitlement SET state=? WHERE order_id=?", state, orderId);
    }

    private void event(long orderId, long actor, String operation, Long from, Long to, long version) {
        sql.update("""
                INSERT INTO booking_event(order_id,actor_id,operation,from_slot_id,to_slot_id,version) VALUES (?,?,?,?,?,?)
                """, orderId, actor, operation, from, to, version);
    }

    private void staff(long actor, long shopId) {
        require(count("SELECT COUNT(*) FROM booking_staff WHERE user_id=? AND shop_id=?", actor, shopId) == 1,
                FORBIDDEN, "没有该门店的操作权限");
    }

    private int count(String query, Object... arguments) {
        return Objects.requireNonNull(sql.queryForObject(query, Integer.class, arguments));
    }

    private LocalDateTime now() {
        return sql.queryForObject("SELECT NOW(6)", LocalDateTime.class);
    }

    private static double distance(Slot a, Slot b) {
        double dLat = Math.toRadians(b.latitude() - a.latitude());
        double dLon = Math.toRadians(b.longitude() - a.longitude());
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(a.latitude())) * Math.cos(Math.toRadians(b.latitude()))
                * Math.pow(Math.sin(dLon / 2), 2);
        return 6371 * 2 * Math.asin(Math.sqrt(Math.min(1, h)));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new BookingException(message);
    }

    private static void require(boolean condition, BookingException.Code code, String message) {
        if (!condition) throw new BookingException(code, message);
    }
}
