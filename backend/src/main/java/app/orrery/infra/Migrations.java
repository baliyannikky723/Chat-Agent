package app.orrery.infra;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Schema migrations, applied in order, once, each in its own transaction. Statements are
 * listed one by one (triggers contain semicolons, so a script cannot be split on ";").
 */
public final class Migrations {

    public record Migration(String version, List<String> statements) {}

    public static final List<Migration> ALL = List.of(new Migration("001_init", List.of(
            """
            create table users (
              id text primary key,
              created_at text not null default (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
              timezone text not null default 'UTC',
              locale text not null default 'en-GB',
              clock_offset_ms integer not null default 0
            )""",
            """
            create table conversations (
              id text primary key,
              user_id text not null references users(id) on delete cascade,
              title text,
              created_at text not null,
              updated_at text not null
            )""",
            "create index conversations_user_updated on conversations(user_id, updated_at desc)",
            // One row per message. A turn = a user message + the assistant reply sharing its turn_id.
            // local_date is the calendar day in the user's timezone: "last Tuesday" is answered against it.
            """
            create table messages (
              seq integer primary key autoincrement,
              id text not null unique,
              conversation_id text not null references conversations(id) on delete cascade,
              user_id text not null references users(id) on delete cascade,
              turn_id text not null,
              role text not null check (role in ('user', 'assistant')),
              content text not null,
              status text not null default 'complete' check (status in ('complete', 'stopped', 'error')),
              created_at text not null,
              local_date text not null,
              timezone text not null,
              keywords text not null default '',
              entities text not null default '[]',
              model text,
              metrics text,
              recall text
            )""",
            "create index messages_conversation on messages(conversation_id, created_at)",
            "create index messages_user_date on messages(user_id, local_date)",
            "create index messages_turn on messages(turn_id)",
            // Full-text index over questions, answers and extracted keywords (porter stemming).
            """
            create virtual table messages_fts using fts5(
              content, keywords, content='messages', content_rowid='seq', tokenize='porter unicode61 remove_diacritics 2'
            )""",
            """
            create trigger messages_ai after insert on messages begin
              insert into messages_fts(rowid, content, keywords) values (new.seq, new.content, new.keywords);
            end""",
            """
            create trigger messages_ad after delete on messages begin
              insert into messages_fts(messages_fts, rowid, content, keywords) values ('delete', old.seq, old.content, old.keywords);
            end""",
            """
            create trigger messages_au after update of content, keywords on messages begin
              insert into messages_fts(messages_fts, rowid, content, keywords) values ('delete', old.seq, old.content, old.keywords);
              insert into messages_fts(rowid, content, keywords) values (new.seq, new.content, new.keywords);
            end""",
            // Pages an answer was built from; n is the [n] citation number.
            """
            create table sources (
              message_id text not null references messages(id) on delete cascade,
              n integer not null,
              url text not null,
              title text not null,
              domain text not null,
              published_at text,
              snippet text not null default '',
              cited integer not null default 0,
              primary key (message_id, n)
            )""",
            """
            create table searches (
              id integer primary key autoincrement,
              message_id text not null references messages(id) on delete cascade,
              query text not null,
              params text not null default '{}',
              provider text,
              result_count integer not null default 0,
              cached integer not null default 0,
              duration_ms integer not null default 0,
              error text,
              created_at text not null
            )""",
            "create index searches_message on searches(message_id)",
            // The claim ledger: time-sensitive facts the agent stated, dated to when they were true.
            """
            create table claims (
              id integer primary key autoincrement,
              user_id text not null references users(id) on delete cascade,
              conversation_id text not null references conversations(id) on delete cascade,
              message_id text not null references messages(id) on delete cascade,
              entity text not null,
              attribute text not null,
              value text not null,
              statement text not null,
              source_url text,
              as_of text not null,
              as_of_local text not null
            )""",
            "create index claims_message on claims(message_id)",
            "create index claims_user_entity on claims(user_id, entity collate nocase)",
            // Durable background work (memory extraction); survives restarts.
            """
            create table jobs (
              id integer primary key autoincrement,
              kind text not null,
              payload text not null,
              status text not null default 'pending' check (status in ('pending', 'running', 'done', 'failed')),
              attempts integer not null default 0,
              run_after integer not null,
              last_error text,
              created_at text not null default (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
              updated_at text not null default (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
            )""",
            "create index jobs_ready on jobs(status, run_after)")));

    private Migrations() {}

    /** Applies pending migrations; returns the versions applied. */
    public static List<String> apply(DataSource dataSource) throws SQLException {
        List<String> applied = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            try (Statement st = c.createStatement()) {
                st.execute("pragma journal_mode = wal");
                st.execute("create table if not exists schema_migrations (version text primary key, applied_at text not null)");
            }
            Set<String> done = new HashSet<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("select version from schema_migrations")) {
                while (rs.next()) done.add(rs.getString(1));
            }
            for (Migration m : ALL) {
                if (done.contains(m.version())) continue;
                boolean auto = c.getAutoCommit();
                c.setAutoCommit(false);
                try (Statement st = c.createStatement()) {
                    for (String sql : m.statements()) st.execute(sql);
                    try (PreparedStatement ps = c.prepareStatement("insert into schema_migrations (version, applied_at) values (?, ?)")) {
                        ps.setString(1, m.version());
                        ps.setString(2, Instant.now().toString());
                        ps.executeUpdate();
                    }
                    c.commit();
                    applied.add(m.version());
                } catch (SQLException e) {
                    c.rollback();
                    throw new SQLException("migration " + m.version() + " failed: " + e.getMessage(), e);
                } finally {
                    c.setAutoCommit(auto);
                }
            }
        }
        return applied;
    }
}
