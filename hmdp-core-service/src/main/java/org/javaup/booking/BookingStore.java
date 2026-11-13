package org.javaup.booking;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

@Component
@ConditionalOnProperty("hmdp.booking.jdbc-url")
class BookingStore {
    private final HikariDataSource dataSource;
    final JdbcTemplate sql;
    final TransactionTemplate tx;
    final TransactionTemplate snapshot;

    BookingStore(@Value("${hmdp.booking.jdbc-url}") String url,
                 @Value("${hmdp.booking.username}") String username,
                 @Value("${hmdp.booking.password:${DB_PASSWORD:}}") String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setPoolName("booking");
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(1);
        dataSource = new HikariDataSource(config);
        sql = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        snapshot = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
    }

    @PreDestroy
    void close() {
        dataSource.close();
    }
}
