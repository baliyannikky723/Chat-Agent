package app.orrery.infra;

import app.orrery.core.json.Json;
import app.orrery.core.json.JsonException;
import app.orrery.core.store.Store;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;

/**
 * {@link Store} on SQLite via plain JDBC. SQLite is the right database for this app: one
 * file, zero setup on any OS, ACID, and FTS5 full-text search with BM25 ranking built in.
 * Every statement is parameterised; deletes clean child rows explicitly so correctness
 * never depends on a per-connection pragma.
 */
public final class SqliteStore implements Store {

    private final DataSource dataSource;

    public SqliteStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection c) throws SQLException;
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private Connection open() throws SQLException {
        Connection c = dataSource.getConnection();
        try (Statement st = c.createStatement()) {
            st.execute("pragma foreign_keys = on");
            st.execute("pragma busy_timeout = 5000");
        }
        return c;
    }

    private <T> T work(SqlWork<T> work) {
        try (Connection c = open()) {
            return work.run(c);
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    private <T> T transaction(SqlWork<T> work) {
        try (Connection c = open()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T out = work.run(c);
                c.commit();
                return out;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            int idx = i + 1;
            if (p == null) ps.setNull(idx, Types.VARCHAR);
            else if (p instanceof String s) ps.setString(idx, s);
            else if (p instanceof Integer n) ps.setInt(idx, n);
            else if (p instanceof Long n) ps.setLong(idx, n);
            else if (p instanceof Double d) ps.setDouble(idx, d);
            else if (p instanceof Boolean b) ps.setInt(idx, b ? 1 : 0);
            else if (p instanceof LocalDate d) ps.setString(idx, d.toString());
            else ps.setString(idx, p.toString());
        }
    }

    private static <T> List<T> query(Connection c, String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(mapper.map(rs));
                return out;
            }
        }
    }

    private static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    private static String json(List<String> ids) {
        return Json.write(ids == null ? List.of() : ids);
    }

    private static List<String> stringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            Object v = Json.parse(json);
            if (v instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        } catch (JsonException ignored) {
            // corrupt value: treat as empty
        }
        return List.of();
    }

    private static LocalDate date(String s) {
        return s == null ? null : LocalDate.parse(s);
    }

    public static final class StoreException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        StoreException(SQLException cause) {
            super("database error: " + cause.getMessage(), cause);
        }
    }

    // ── users ───────────────────────────────────────────────────────────────

    private static UserRow mapUser(ResultSet rs) throws SQLException {
        return new UserRow(rs.getString("id"), rs.getString("timezone"), rs.getString("locale"), rs.getLong("clock_offset_ms"));
    }

    @Override
    public UserRow ensureUser(String id, String timezone, String locale) {
        return work(c -> {
            update(c, """
                    insert into users (id, timezone, locale) values (?, ?, ?)
                    on conflict (id) do update set timezone = excluded.timezone, locale = excluded.locale""", id, timezone, locale);
            return query(c, "select id, timezone, locale, clock_offset_ms from users where id = ?", SqliteStore::mapUser, id).get(0);
        });
    }

    @Override
    public UserRow user(String id) {
        return work(c -> {
            List<UserRow> rows = query(c, "select id, timezone, locale, clock_offset_ms from users where id = ?", SqliteStore::mapUser, id);
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    @Override
    public void setClockOffset(String userId, long offsetMillis) {
        work(c -> update(c, "update users set clock_offset_ms = ? where id = ?", offsetMillis, userId));
    }

    @Override
    public void deleteUserData(String userId) {
        transaction(c -> {
            update(c, "delete from claims where user_id = ?", userId);
            update(c, "delete from sources where message_id in (select id from messages where user_id = ?)", userId);
            update(c, "delete from searches where message_id in (select id from messages where user_id = ?)", userId);
            update(c, "delete from messages where user_id = ?", userId);
            update(c, "delete from conversations where user_id = ?", userId);
            return null;
        });
    }

    // ── conversations ───────────────────────────────────────────────────────

    private static ConversationRow mapConversation(ResultSet rs) throws SQLException {
        return new ConversationRow(rs.getString("id"), rs.getString("user_id"), rs.getString("title"), rs.getString("created_at"),
                rs.getString("updated_at"));
    }

    @Override
    public ConversationRow createConversation(String id, String userId, String createdAt) {
        return work(c -> {
            update(c, "insert into conversations (id, user_id, title, created_at, updated_at) values (?, ?, null, ?, ?)", id, userId,
                    createdAt, createdAt);
            return new ConversationRow(id, userId, null, createdAt, createdAt);
        });
    }

    @Override
    public ConversationRow conversation(String userId, String id) {
        return work(c -> {
            List<ConversationRow> rows = query(c,
                    "select id, user_id, title, created_at, updated_at from conversations where id = ? and user_id = ?",
                    SqliteStore::mapConversation, id, userId);
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    @Override
    public List<ConversationSummary> listConversations(String userId, int limit) {
        return work(c -> query(c, """
                select c.id, c.title, c.created_at, c.updated_at,
                       coalesce(max(m.local_date), substr(c.created_at, 1, 10)) as last_local_date,
                       coalesce(sum(case when m.role = 'user' then 1 else 0 end), 0) as turns,
                       (select mm.content from messages mm where mm.conversation_id = c.id and mm.role = 'user'
                         order by mm.created_at limit 1) as first_question
                  from conversations c
                  left join messages m on m.conversation_id = c.id
                 where c.user_id = ?
                 group by c.id
                 order by c.updated_at desc
                 limit ?""", rs -> {
            String title = rs.getString("title");
            if (title == null || title.isBlank()) title = app.orrery.core.util.Text.firstWords(rs.getString("first_question"), 7);
            if (title == null || title.isBlank()) title = "New conversation";
            return new ConversationSummary(rs.getString("id"), title, rs.getString("created_at"), rs.getString("updated_at"),
                    rs.getString("last_local_date"), rs.getInt("turns"));
        }, userId, limit));
    }

    @Override
    public void touchConversation(String id, String at) {
        work(c -> update(c, "update conversations set updated_at = max(updated_at, ?) where id = ?", at, id));
    }

    @Override
    public boolean setTitleIfEmpty(String conversationId, String title) {
        return work(c -> update(c, "update conversations set title = ? where id = ? and (title is null or title = '')", title,
                conversationId) > 0);
    }

    @Override
    public boolean renameConversation(String userId, String id, String title) {
        return work(c -> update(c, "update conversations set title = ? where id = ? and user_id = ?", title, id, userId) > 0);
    }

    @Override
    public boolean deleteConversation(String userId, String id) {
        return transaction(c -> {
            if (query(c, "select 1 from conversations where id = ? and user_id = ?", rs -> 1, id, userId).isEmpty()) return false;
            update(c, "delete from claims where conversation_id = ?", id);
            update(c, "delete from sources where message_id in (select id from messages where conversation_id = ?)", id);
            update(c, "delete from searches where message_id in (select id from messages where conversation_id = ?)", id);
            update(c, "delete from messages where conversation_id = ?", id);
            update(c, "delete from conversations where id = ?", id);
            return true;
        });
    }

    // ── messages ────────────────────────────────────────────────────────────

    private static final String MESSAGE_COLUMNS = """
            id, conversation_id, user_id, turn_id, role, content, status, created_at, local_date, timezone, keywords, entities,
            model, metrics, recall""";

    private static MessageRow mapMessage(ResultSet rs) throws SQLException {
        return new MessageRow(rs.getString("id"), rs.getString("conversation_id"), rs.getString("user_id"), rs.getString("turn_id"),
                rs.getString("role"), rs.getString("content"), rs.getString("status"), rs.getString("created_at"),
                date(rs.getString("local_date")), rs.getString("timezone"), rs.getString("keywords"), stringList(rs.getString("entities")),
                rs.getString("model"), rs.getString("metrics"), rs.getString("recall"));
    }

    @Override
    public void insertMessage(MessageRow m) {
        work(c -> update(c, """
                insert into messages (id, conversation_id, user_id, turn_id, role, content, status, created_at, local_date, timezone,
                                      keywords, entities, model, metrics, recall)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                m.id(), m.conversationId(), m.userId(), m.turnId(), m.role(), m.content(), m.status(), m.createdAt(), m.localDate(),
                m.timezone(), m.keywords() == null ? "" : m.keywords(), json(m.entities()), m.model(), m.metricsJson(), m.recallJson()));
    }

    @Override
    public MessageRow message(String userId, String id) {
        return work(c -> {
            List<MessageRow> rows = query(c, "select " + MESSAGE_COLUMNS + " from messages where id = ? and user_id = ?",
                    SqliteStore::mapMessage, id, userId);
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    @Override
    public List<MessageRow> conversationMessages(String conversationId, int limit) {
        return work(c -> {
            List<MessageRow> rows = query(c, "select " + MESSAGE_COLUMNS
                    + " from messages where conversation_id = ? order by created_at desc, seq desc limit ?",
                    SqliteStore::mapMessage, conversationId, limit);
            List<MessageRow> out = new ArrayList<>(rows);
            java.util.Collections.reverse(out);
            return out;
        });
    }

    @Override
    public void setMessageMemory(String messageId, String keywords, List<String> entities) {
        work(c -> update(c, "update messages set keywords = ?, entities = ? where id = ?", keywords, json(entities), messageId));
    }

    @Override
    public void insertSources(String messageId, List<SourceRow> sources) {
        if (sources.isEmpty()) return;
        transaction(c -> {
            for (SourceRow s : sources) {
                update(c, """
                        insert or ignore into sources (message_id, n, url, title, domain, published_at, snippet, cited)
                        values (?, ?, ?, ?, ?, ?, ?, ?)""",
                        messageId, s.n(), s.url(), s.title(), s.domain(), s.publishedAt(), s.snippet() == null ? "" : s.snippet(), s.cited());
            }
            return null;
        });
    }

    @Override
    public Map<String, List<SourceRow>> sourcesFor(List<String> messageIds) {
        Map<String, List<SourceRow>> out = new LinkedHashMap<>();
        if (messageIds == null || messageIds.isEmpty()) return out;
        return work(c -> {
            query(c, """
                    select message_id, n, url, title, domain, published_at, snippet, cited from sources
                     where message_id in (select value from json_each(?)) order by message_id, n""", rs -> {
                out.computeIfAbsent(rs.getString("message_id"), k -> new ArrayList<>()).add(new SourceRow(rs.getInt("n"),
                        rs.getString("url"), rs.getString("title"), rs.getString("domain"), rs.getString("published_at"),
                        rs.getString("snippet"), rs.getInt("cited") == 1));
                return null;
            }, json(messageIds));
            return out;
        });
    }

    @Override
    public void insertSearches(String messageId, List<SearchLog> searches) {
        if (searches.isEmpty()) return;
        transaction(c -> {
            for (SearchLog s : searches) {
                update(c, """
                        insert into searches (message_id, query, params, provider, result_count, cached, duration_ms, error, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                        messageId, s.query(), s.paramsJson(), s.provider(), s.resultCount(), s.cached(), s.durationMs(), s.error(), s.createdAt());
            }
            return null;
        });
    }

    @Override
    public Map<String, List<SearchLog>> searchesFor(List<String> messageIds) {
        Map<String, List<SearchLog>> out = new LinkedHashMap<>();
        if (messageIds == null || messageIds.isEmpty()) return out;
        return work(c -> {
            query(c, """
                    select message_id, query, params, provider, result_count, cached, duration_ms, error, created_at from searches
                     where message_id in (select value from json_each(?)) order by message_id, id""", rs -> {
                out.computeIfAbsent(rs.getString("message_id"), k -> new ArrayList<>()).add(new SearchLog(rs.getString("query"),
                        rs.getString("params"), rs.getString("provider"), rs.getInt("result_count"), rs.getInt("cached") == 1,
                        rs.getLong("duration_ms"), rs.getString("error"), rs.getString("created_at")));
                return null;
            }, json(messageIds));
            return out;
        });
    }

    // ── recall ──────────────────────────────────────────────────────────────

    private static final String TURN_COLUMNS = """
            u.id as user_message_id, u.conversation_id, coalesce(c.title, '') as title, u.content as question, u.created_at,
            u.local_date, u.timezone, a.id as assistant_message_id, a.content as answer, coalesce(a.entities, '[]') as entities""";

    private static final String TURN_JOINS = """
              join conversations c on c.id = u.conversation_id
              left join messages a on a.turn_id = u.id and a.role = 'assistant'
            """;

    private static TurnRow mapTurn(ResultSet rs) throws SQLException {
        return new TurnRow(rs.getString("user_message_id"), rs.getString("conversation_id"), rs.getString("title"),
                rs.getString("question"), rs.getString("created_at"), date(rs.getString("local_date")), rs.getString("timezone"),
                rs.getString("assistant_message_id"), rs.getString("answer"), stringList(rs.getString("entities")), rs.getDouble("score"));
    }

    static final String SQL_TURNS_IN_RANGE = "select " + TURN_COLUMNS + ", 0.0 as score from messages u " + TURN_JOINS + """
             where u.user_id = ? and u.role = 'user' and u.local_date between ? and ?
               and u.id not in (select value from json_each(?))
             order by u.created_at asc
             limit ?""";

    // bm25() cannot run inside an aggregate, so rank first (materialized), then group by turn.
    static final String SQL_SEARCH_TURNS = """
            with matched as materialized (
              select m.turn_id as turn_id, bm25(messages_fts, 1.0, 1.5) as rank
                from messages_fts join messages m on m.seq = messages_fts.rowid
               where messages_fts match ? and m.user_id = ?
                 and (? is null or m.local_date >= ?) and (? is null or m.local_date <= ?)
            ),
            hits as (select turn_id, min(rank) as rank from matched group by turn_id)
            """ + "select " + TURN_COLUMNS + ", -h.rank as score from hits h join messages u on u.id = h.turn_id and u.role = 'user' "
            + TURN_JOINS + """
             where u.id not in (select value from json_each(?))
             order by h.rank asc, u.created_at desc
             limit ?""";

    static final String SQL_RECENT_TURNS = "select " + TURN_COLUMNS + ", 0.0 as score from messages u " + TURN_JOINS + """
             where u.user_id = ? and u.role = 'user' and (? is null or u.conversation_id <> ?)
             order by u.created_at desc
             limit ?""";

    static final String SQL_TURN_BY_ANSWER = "select " + TURN_COLUMNS + ", 0.0 as score from messages u " + TURN_JOINS + """
             where u.user_id = ? and u.role = 'user' and a.id = ?""";

    @Override
    public List<TurnRow> turnsInRange(String userId, LocalDate start, LocalDate end, List<String> exclude, int limit) {
        return work(c -> query(c, SQL_TURNS_IN_RANGE, SqliteStore::mapTurn, userId, start, end, json(exclude), limit));
    }

    @Override
    public List<TurnRow> searchTurns(String userId, String ftsQuery, LocalDate start, LocalDate end, List<String> exclude, int limit) {
        if (ftsQuery == null || ftsQuery.isBlank()) return List.of();
        return work(c -> query(c, SQL_SEARCH_TURNS, SqliteStore::mapTurn, ftsQuery, userId, start, start, end, end, json(exclude), limit));
    }

    @Override
    public List<TurnRow> recentTurns(String userId, String excludeConversationId, int limit) {
        return work(c -> query(c, SQL_RECENT_TURNS, SqliteStore::mapTurn, userId, excludeConversationId, excludeConversationId, limit));
    }

    @Override
    public TurnRow turnByAssistantMessage(String userId, String assistantMessageId) {
        return work(c -> {
            List<TurnRow> rows = query(c, SQL_TURN_BY_ANSWER, SqliteStore::mapTurn, userId, assistantMessageId);
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    @Override
    public HistoryBounds historyBounds(String userId, List<String> exclude) {
        return work(c -> query(c, """
                select min(local_date) as first, max(local_date) as last, count(*) as turns from messages
                 where user_id = ? and role = 'user' and id not in (select value from json_each(?))""",
                rs -> new HistoryBounds(date(rs.getString("first")), date(rs.getString("last")), rs.getInt("turns")),
                userId, json(exclude)).get(0));
    }

    @Override
    public Map<LocalDate, Integer> turnCountsByDay(String userId, LocalDate start, LocalDate end, List<String> exclude) {
        Map<LocalDate, Integer> out = new TreeMap<>();
        work(c -> query(c, """
                select local_date, count(*) as n from messages
                 where user_id = ? and role = 'user' and local_date between ? and ? and id not in (select value from json_each(?))
                 group by local_date order by local_date""", rs -> {
            out.put(date(rs.getString("local_date")), rs.getInt("n"));
            return null;
        }, userId, start, end, json(exclude)));
        return out;
    }

    @Override
    public List<IndexEntry> conversationIndex(String userId, String excludeConversationId, int limit) {
        return work(c -> {
            List<String[]> convs = query(c, """
                    select c.id, coalesce(c.title, '') as title, min(m.local_date) as first_date, max(m.created_at) as last_at
                      from conversations c join messages m on m.conversation_id = c.id
                     where c.user_id = ? and (? is null or c.id <> ?)
                     group by c.id order by last_at desc limit ?""",
                    rs -> new String[] {rs.getString("id"), rs.getString("title"), rs.getString("first_date")},
                    userId, excludeConversationId, excludeConversationId, limit);
            List<IndexEntry> out = new ArrayList<>();
            for (String[] row : convs) {
                String title = row[1];
                if (title.isBlank()) {
                    List<String> q = query(c, "select content from messages where conversation_id = ? and role = 'user' order by created_at limit 1",
                            rs -> rs.getString(1), row[0]);
                    title = q.isEmpty() ? "Untitled" : app.orrery.core.util.Text.firstWords(q.get(0), 7);
                }
                List<String> entities = new ArrayList<>();
                for (String e : query(c, "select entities from messages where conversation_id = ? and role = 'assistant' order by created_at",
                        rs -> rs.getString(1), row[0])) {
                    for (String name : stringList(e)) if (!entities.contains(name) && entities.size() < 8) entities.add(name);
                }
                out.add(new IndexEntry(row[0], title, date(row[2]), entities));
            }
            return out;
        });
    }

    // ── claims ──────────────────────────────────────────────────────────────

    private static ClaimRow mapClaim(ResultSet rs) throws SQLException {
        return new ClaimRow(rs.getLong("id"), rs.getString("message_id"), rs.getString("conversation_id"), rs.getString("entity"),
                rs.getString("attribute"), rs.getString("value"), rs.getString("statement"), rs.getString("source_url"),
                rs.getString("as_of"), date(rs.getString("as_of_local")));
    }

    @Override
    public void replaceClaims(String userId, String conversationId, String messageId, String asOf, LocalDate asOfLocal, List<NewClaim> claims) {
        transaction(c -> {
            update(c, "delete from claims where message_id = ?", messageId);
            for (NewClaim cl : claims) {
                update(c, """
                        insert into claims (user_id, conversation_id, message_id, entity, attribute, value, statement, source_url, as_of, as_of_local)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                        userId, conversationId, messageId, cl.entity(), cl.attribute(), cl.value(), cl.statement(), cl.sourceUrl(), asOf, asOfLocal);
            }
            return null;
        });
    }

    @Override
    public Map<String, List<ClaimRow>> claimsFor(List<String> messageIds) {
        Map<String, List<ClaimRow>> out = new LinkedHashMap<>();
        if (messageIds == null || messageIds.isEmpty()) return out;
        work(c -> query(c, """
                select id, message_id, conversation_id, entity, attribute, value, statement, source_url, as_of, as_of_local
                  from claims where message_id in (select value from json_each(?)) order by id""", rs -> {
            ClaimRow row = mapClaim(rs);
            out.computeIfAbsent(row.messageId(), k -> new ArrayList<>()).add(row);
            return null;
        }, json(messageIds)));
        return out;
    }

    @Override
    public List<ClaimRow> ledger(String userId, int limit) {
        return work(c -> query(c, """
                select id, message_id, conversation_id, entity, attribute, value, statement, source_url, as_of, as_of_local
                  from claims where user_id = ?
                 order by entity collate nocase, attribute collate nocase, as_of desc
                 limit ?""", SqliteStore::mapClaim, userId, limit));
    }

    @Override
    public List<TimelineRow> timeline(String userId, LocalDate since) {
        DateTimeFormatter hm = DateTimeFormatter.ofPattern("HH:mm");
        return work(c -> query(c, """
                select u.id as user_message_id, u.conversation_id, coalesce(c.title, '') as title, u.content as question,
                       u.created_at, u.local_date, u.timezone, a.id as assistant_message_id,
                       (select count(*) from claims cl where cl.message_id = a.id) as claims
                  from messages u
                  join conversations c on c.id = u.conversation_id
                  left join messages a on a.turn_id = u.id and a.role = 'assistant'
                 where u.user_id = ? and u.role = 'user' and u.local_date >= ?
                 order by u.created_at desc
                 limit 500""", rs -> {
            String time;
            try {
                time = hm.format(Instant.parse(rs.getString("created_at")).atZone(ZoneId.of(rs.getString("timezone"))));
            } catch (DateTimeException e) {
                time = "";
            }
            return new TimelineRow(rs.getString("conversation_id"), rs.getString("title"), rs.getString("user_message_id"),
                    rs.getString("assistant_message_id"), rs.getString("question"), date(rs.getString("local_date")), time, rs.getInt("claims"));
        }, userId, since));
    }

    // ── jobs ────────────────────────────────────────────────────────────────

    @Override
    public void enqueueJob(String kind, String payloadJson, long runAfterEpochMillis) {
        work(c -> update(c, "insert into jobs (kind, payload, run_after) values (?, ?, ?)", kind, payloadJson, runAfterEpochMillis));
    }

    @Override
    public List<JobRow> claimJobs(int limit, long nowEpochMillis) {
        if (limit <= 0) return List.of();
        return transaction(c -> {
            List<JobRow> ready = query(c, """
                    select id, kind, payload, attempts from jobs
                     where status = 'pending' and run_after <= ? order by id limit ?""",
                    rs -> new JobRow(rs.getLong("id"), rs.getString("kind"), rs.getString("payload"), rs.getInt("attempts") + 1),
                    nowEpochMillis, limit);
            for (JobRow j : ready) {
                update(c, "update jobs set status = 'running', attempts = attempts + 1, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') where id = ?", j.id());
            }
            return ready;
        });
    }

    @Override
    public void finishJob(long id) {
        work(c -> update(c, "update jobs set status = 'done', last_error = null, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') where id = ?", id));
    }

    @Override
    public void failJob(long id, String error, Long retryAtEpochMillis) {
        String err = error == null ? null : error.length() > 2000 ? error.substring(0, 2000) : error;
        if (retryAtEpochMillis == null) {
            work(c -> update(c, "update jobs set status = 'failed', last_error = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') where id = ?", err, id));
        } else {
            work(c -> update(c, "update jobs set status = 'pending', last_error = ?, run_after = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') where id = ?",
                    err, retryAtEpochMillis, id));
        }
    }

    @Override
    public int resetRunningJobs() {
        return work(c -> update(c, "update jobs set status = 'pending' where status = 'running'"));
    }

    @Override
    public int pendingJobs() {
        return work(c -> query(c, "select count(*) from jobs where status in ('pending', 'running')", rs -> rs.getInt(1)).get(0));
    }
}
