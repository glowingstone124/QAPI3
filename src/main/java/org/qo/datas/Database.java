package org.qo.datas;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class Database {
    private static final AtomicBoolean cachedAvailable = new AtomicBoolean(true);
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    static {
        scheduler.scheduleAtFixedRate(Database::refreshSQLStatus, 0, 1, TimeUnit.SECONDS);
    }
    private static void refreshSQLStatus() {
        try (Connection connection = ConnectionPool.getConnection()) {
            cachedAvailable.set(true);
        } catch (SQLException e) {
            cachedAvailable.set(false);
        }
    }

    public static boolean SQLAvailable() {
        return cachedAvailable.get();
    }

    public static void shutdown() {
        scheduler.shutdown();
    }
}
