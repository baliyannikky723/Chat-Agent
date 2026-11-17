package app.orrery.support;

import app.orrery.core.store.Store.MessageRow;
import app.orrery.infra.Migrations;
import app.orrery.infra.SqliteStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.sqlite.SQLiteDataSource;

/** A fresh SQLite database file per test, with migrations applied. */
public final class TestDb {

    private TestDb() {}

    public static SqliteStore fresh() {
        try {
            Path dir = Files.createTempDirectory("orrery-test");
            SQLiteDataSource ds = new SQLiteDataSource();
            ds.setUrl("jdbc:sqlite:" + dir.resolve("test.db").toString().replace('\\', '/'));
            Migrations.apply(ds);
            return new SqliteStore(ds);
        } catch (IOException | SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Inserts one past turn: a question at {@code time} UTC on {@code day} and an answer 9 s later. */
    public static void turn(SqliteStore store, String conv, String userId, String uId, String aId, LocalDate day, String time,
                            String question, String answer) {
        String at = day + "T" + time + ":00.000Z";
        store.insertMessage(new MessageRow(uId, conv, userId, uId, "user", question, "complete", at, day, "Asia/Kolkata", "", List.of(), null, null, null));
        if (answer != null) {
            store.insertMessage(new MessageRow(aId, conv, userId, uId, "assistant", answer, "complete", day + "T" + time + ":09.000Z", day,
                    "Asia/Kolkata", "", List.of(), "test-model", "{\"totalMs\":9000}", null));
        }
        store.touchConversation(conv, at);
    }
}
