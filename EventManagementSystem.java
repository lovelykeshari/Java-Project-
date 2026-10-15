/*
 * ============================================================================
 *  ONLINE EVENT MANAGEMENT SYSTEM  -  pure Java (JDK only, no external libs)
 * ============================================================================
 *  Roles      : Admin, Event Organizer, Attendee (each with its own dashboard)
 *
 *  Review-1 rubric coverage
 *  ------------------------
 *  1. Problem understanding & solution design
 *       -> layered design: Models -> DataStore (persistence) -> Services -> UI
 *  2. Core Java concepts
 *       -> OOP        : abstract User + Admin/Organizer/Attendee, interfaces
 *                       (DataStore, PaymentProcessor), encapsulation, enums,
 *                       records, polymorphism
 *       -> Collections: Map, List, Deque, Set, Optional, Streams
 *       -> Exceptions : custom checked exception hierarchy (EMSException ...)
 *       -> Threads    : ExecutorService (async notifications, concurrent
 *                       ticket-purchase demo, multi-threaded web server),
 *                       synchronized store, ConcurrentHashMap, AtomicInteger
 *  3. Database integration (JDBC)
 *       -> JdbcStore: schema design (7 tables, FKs), CRUD with
 *          PreparedStatement, transaction handling (commit / rollback)
 *  4. Servlets & web integration (request/response + session management)
 *       -> WebServer built on the JDK's com.sun.net.httpserver (servlet-style
 *          request/response cycle), cookie based SessionManager
 *
 *  Compile & run
 *  -------------
 *      javac EventManagementSystem.java
 *      java EventManagementSystem                 (console, in-memory data)
 *      java EventManagementSystem --demo          (sample data + thread demo)
 *      java EventManagementSystem --web --port 8080
 *      java EventManagementSystem --jdbc-url jdbc:sqlite:ems.db   (needs driver on classpath)
 *
 *  Default admin  : admin@ems.com / admin123
 * ============================================================================
 */

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/* ========================================================================== */
/*  1. ENUMS & EXCEPTIONS                                                     */
/* ========================================================================== */

enum Role { ADMIN, ORGANIZER, ATTENDEE }

enum EventStatus { PENDING, APPROVED, REJECTED }

/** Base checked exception for every expected failure in the application. */
class EMSException extends Exception {
    EMSException(String message) { super(message); }
    EMSException(String message, Throwable cause) { super(message, cause); }
}
class ValidationException extends EMSException { ValidationException(String m) { super(m); } }
class NotFoundException extends EMSException { NotFoundException(String m) { super(m); } }
class AuthException extends EMSException { AuthException(String m) { super(m); } }
class AccessDeniedException extends EMSException { AccessDeniedException(String m) { super(m); } }
class SoldOutException extends EMSException { SoldOutException(String m) { super(m); } }

/* ========================================================================== */
/*  2. DOMAIN MODEL (OOP: abstraction, inheritance, encapsulation)            */
/* ========================================================================== */

/** Abstract base class of all system users. */
abstract class User {
    private int id;
    private String name;
    private String email;
    private String password;          // SHA-256 hash, never plain text
    private final Role role;
    private String city = "";
    private String preferences = "";

    protected User(int id, String name, String email, String password, Role role) {
        this.id = id; this.name = name; this.email = email; this.password = password; this.role = role;
    }

    /** Factory method - picks the right subclass for a role (polymorphism). */
    static User create(int id, String name, String email, String password, Role role) {
        return switch (role) {
            case ADMIN -> new Admin(id, name, email, password);
            case ORGANIZER -> new Organizer(id, name, email, password);
            case ATTENDEE -> new Attendee(id, name, email, password);
        };
    }

    abstract String dashboardTitle();

    int getId() { return id; }
    void setId(int id) { this.id = id; }
    String getName() { return name; }
    void setName(String name) { this.name = name; }
    String getEmail() { return email; }
    void setEmail(String email) { this.email = email; }
    String getPassword() { return password; }
    void setPassword(String password) { this.password = password; }
    Role getRole() { return role; }
    String getCity() { return city; }
    void setCity(String city) { this.city = city == null ? "" : city; }
    String getPreferences() { return preferences; }
    void setPreferences(String p) { this.preferences = p == null ? "" : p; }

    @Override public String toString() {
        return String.format("#%d %s <%s> [%s]", id, name, email, role);
    }
}

class Admin extends User {
    Admin(int id, String name, String email, String password) { super(id, name, email, password, Role.ADMIN); }
    @Override String dashboardTitle() { return "ADMIN DASHBOARD"; }
}

class Organizer extends User {
    Organizer(int id, String name, String email, String password) { super(id, name, email, password, Role.ORGANIZER); }
    @Override String dashboardTitle() { return "EVENT ORGANIZER DASHBOARD"; }
}

class Attendee extends User {
    Attendee(int id, String name, String email, String password) { super(id, name, email, password, Role.ATTENDEE); }
    @Override String dashboardTitle() { return "ATTENDEE DASHBOARD"; }
}

class Event {
    private int id;
    private final int organizerId;
    private String title;
    private String description;
    private LocalDate date;
    private LocalTime time;
    private String venue;
    private EventStatus status;

    Event(int id, int organizerId, String title, String description,
          LocalDate date, LocalTime time, String venue, EventStatus status) {
        this.id = id; this.organizerId = organizerId; this.title = title; this.description = description;
        this.date = date; this.time = time; this.venue = venue; this.status = status;
    }

    int getId() { return id; }
    void setId(int id) { this.id = id; }
    int getOrganizerId() { return organizerId; }
    String getTitle() { return title; }
    void setTitle(String t) { this.title = t; }
    String getDescription() { return description; }
    void setDescription(String d) { this.description = d; }
    LocalDate getDate() { return date; }
    void setDate(LocalDate d) { this.date = d; }
    LocalTime getTime() { return time; }
    void setTime(LocalTime t) { this.time = t; }
    String getVenue() { return venue; }
    void setVenue(String v) { this.venue = v; }
    EventStatus getStatus() { return status; }
    void setStatus(EventStatus s) { this.status = s; }

    boolean isUpcoming() { return !date.isBefore(LocalDate.now()); }

    @Override public String toString() {
        return String.format("#%-3d %-28s %s %s  %-18s [%s]", id, title, date, time, venue, status);
    }
}

class Ticket {
    private int id;
    private final int eventId;
    private double price;
    private int quantity;
    private int sold;

    Ticket(int id, int eventId, double price, int quantity, int sold) {
        this.id = id; this.eventId = eventId; this.price = price; this.quantity = quantity; this.sold = sold;
    }
    int getId() { return id; }
    void setId(int id) { this.id = id; }
    int getEventId() { return eventId; }
    double getPrice() { return price; }
    void setPrice(double p) { this.price = p; }
    int getQuantity() { return quantity; }
    void setQuantity(int q) { this.quantity = q; }
    int getSold() { return sold; }
    void setSold(int s) { this.sold = s; }
    int available() { return quantity - sold; }

    @Override public String toString() {
        return String.format("Ticket #%d  price=%.2f  total=%d  sold=%d  available=%d", id, price, quantity, sold, available());
    }
}

/** Immutable value objects (Java records). */
record Registration(int id, int eventId, int attendeeId, LocalDateTime registeredAt, String status) { }
record Purchase(int id, int ticketId, int eventId, int attendeeId, int quantity, double amount, LocalDateTime purchasedAt) { }
record Notification(int id, int userId, String message, LocalDateTime createdAt) { }

/* ========================================================================== */
/*  3. UTILITIES: validation, hashing, activity log                           */
/* ========================================================================== */

final class Validator {
    private static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$");
    private Validator() { }

    static String required(String value, String field) throws ValidationException {
        if (value == null || value.isBlank()) throw new ValidationException(field + " is required.");
        return value.trim();
    }
    static String email(String value) throws ValidationException {
        String v = required(value, "Email");
        if (!EMAIL.matcher(v).matches()) throw new ValidationException("Invalid email format: " + v);
        return v.toLowerCase();
    }
    static void positive(double value, String field) throws ValidationException {
        if (value < 0) throw new ValidationException(field + " cannot be negative.");
    }
    /** Luhn checksum used by the mock payment gateway. */
    static boolean luhn(String number) {
        int sum = 0; boolean alt = false;
        for (int i = number.length() - 1; i >= 0; i--) {
            int d = number.charAt(i) - '0';
            if (alt) { d *= 2; if (d > 9) d -= 9; }
            sum += d; alt = !alt;
        }
        return sum % 10 == 0;
    }
}

final class Security {
    private Security() { }
    static String hash(String plain) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(plain.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

/** Thread-safe, bounded activity log (System Activity Monitoring). */
final class ActivityLog {
    private static final Deque<String> ENTRIES = new ConcurrentLinkedDeque<>();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private ActivityLog() { }

    static void record(String message) {
        ENTRIES.addLast(LocalDateTime.now().format(FMT) + " | [" + Thread.currentThread().getName() + "] " + message);
        while (ENTRIES.size() > 200) ENTRIES.pollFirst();
    }
    static List<String> recent(int n) {
        List<String> all = new ArrayList<>(ENTRIES);
        return all.subList(Math.max(0, all.size() - n), all.size());
    }
}

/* ========================================================================== */
/*  4. PERSISTENCE LAYER                                                      */
/* ========================================================================== */

/** Storage abstraction. Services only depend on this interface. */
interface DataStore {
    User addUser(User u) throws EMSException;
    void updateUser(User u) throws EMSException;
    void deleteUser(int id) throws EMSException;
    Optional<User> findUser(int id) throws EMSException;
    Optional<User> findUserByEmail(String email) throws EMSException;
    List<User> users() throws EMSException;

    Event addEvent(Event e) throws EMSException;
    void updateEvent(Event e) throws EMSException;
    void deleteEvent(int id) throws EMSException;
    Optional<Event> findEvent(int id) throws EMSException;
    List<Event> events() throws EMSException;

    Ticket addTicket(Ticket t) throws EMSException;
    void updateTicket(Ticket t) throws EMSException;
    Optional<Ticket> findTicket(int id) throws EMSException;
    List<Ticket> ticketsForEvent(int eventId) throws EMSException;

    Registration addRegistration(Registration r) throws EMSException;
    List<Registration> registrations() throws EMSException;

    /** Atomically reserves tickets and records the purchase. */
    Purchase purchase(int ticketId, int attendeeId, int qty) throws EMSException;
    List<Purchase> purchases() throws EMSException;

    void addNotification(Notification n) throws EMSException;
    List<Notification> notificationsFor(int userId) throws EMSException;

    String getSetting(String key, String defaultValue) throws EMSException;
    void setSetting(String key, String value) throws EMSException;
    Map<String, String> settings() throws EMSException;
}

/** Default store: everything lives in Java collections (no database needed). */
class InMemoryStore implements DataStore {
    private final Map<Integer, User> users = new TreeMap<>();
    private final Map<Integer, Event> events = new TreeMap<>();
    private final Map<Integer, Ticket> tickets = new TreeMap<>();
    private final List<Registration> registrations = new ArrayList<>();
    private final List<Purchase> purchases = new ArrayList<>();
    private final List<Notification> notifications = new ArrayList<>();
    private final Map<String, String> settings = new TreeMap<>();
    private final AtomicInteger userSeq = new AtomicInteger(1), eventSeq = new AtomicInteger(1),
            ticketSeq = new AtomicInteger(1), regSeq = new AtomicInteger(1),
            purchaseSeq = new AtomicInteger(1), noteSeq = new AtomicInteger(1);

    @Override public synchronized User addUser(User u) throws EMSException {
        if (findUserByEmail(u.getEmail()).isPresent()) throw new ValidationException("Email already registered: " + u.getEmail());
        u.setId(userSeq.getAndIncrement());
        users.put(u.getId(), u);
        return u;
    }
    @Override public synchronized void updateUser(User u) throws EMSException {
        if (!users.containsKey(u.getId())) throw new NotFoundException("User not found: " + u.getId());
        Optional<User> clash = findUserByEmail(u.getEmail());
        if (clash.isPresent() && clash.get().getId() != u.getId()) throw new ValidationException("Email already in use: " + u.getEmail());
        users.put(u.getId(), u);
    }
    @Override public synchronized void deleteUser(int id) throws EMSException {
        if (users.remove(id) == null) throw new NotFoundException("User not found: " + id);
        List<Integer> owned = events.values().stream().filter(e -> e.getOrganizerId() == id).map(Event::getId).toList();
        for (int eventId : owned) deleteEvent(eventId);
        registrations.removeIf(r -> r.attendeeId() == id);
        purchases.removeIf(p -> p.attendeeId() == id);
        notifications.removeIf(n -> n.userId() == id);
    }
    @Override public synchronized Optional<User> findUser(int id) { return Optional.ofNullable(users.get(id)); }
    @Override public synchronized Optional<User> findUserByEmail(String email) {
        return users.values().stream().filter(u -> u.getEmail().equalsIgnoreCase(email)).findFirst();
    }
    @Override public synchronized List<User> users() { return new ArrayList<>(users.values()); }

    @Override public synchronized Event addEvent(Event e) { e.setId(eventSeq.getAndIncrement()); events.put(e.getId(), e); return e; }
    @Override public synchronized void updateEvent(Event e) throws EMSException {
        if (!events.containsKey(e.getId())) throw new NotFoundException("Event not found: " + e.getId());
        events.put(e.getId(), e);
    }
    @Override public synchronized void deleteEvent(int id) throws EMSException {
        if (events.remove(id) == null) throw new NotFoundException("Event not found: " + id);
        tickets.values().removeIf(t -> t.getEventId() == id);
        registrations.removeIf(r -> r.eventId() == id);
        purchases.removeIf(p -> p.eventId() == id);
    }
    @Override public synchronized Optional<Event> findEvent(int id) { return Optional.ofNullable(events.get(id)); }
    @Override public synchronized List<Event> events() { return new ArrayList<>(events.values()); }

    @Override public synchronized Ticket addTicket(Ticket t) { t.setId(ticketSeq.getAndIncrement()); tickets.put(t.getId(), t); return t; }
    @Override public synchronized void updateTicket(Ticket t) throws EMSException {
        if (!tickets.containsKey(t.getId())) throw new NotFoundException("Ticket not found: " + t.getId());
        tickets.put(t.getId(), t);
    }
    @Override public synchronized Optional<Ticket> findTicket(int id) { return Optional.ofNullable(tickets.get(id)); }
    @Override public synchronized List<Ticket> ticketsForEvent(int eventId) {
        return tickets.values().stream().filter(t -> t.getEventId() == eventId).collect(Collectors.toList());
    }

    @Override public synchronized Registration addRegistration(Registration r) throws EMSException {
        boolean dup = registrations.stream().anyMatch(x -> x.eventId() == r.eventId() && x.attendeeId() == r.attendeeId());
        if (dup) throw new ValidationException("Already registered/subscribed for this event.");
        Registration saved = new Registration(regSeq.getAndIncrement(), r.eventId(), r.attendeeId(), r.registeredAt(), r.status());
        registrations.add(saved);
        return saved;
    }
    @Override public synchronized List<Registration> registrations() { return new ArrayList<>(registrations); }

    @Override public synchronized Purchase purchase(int ticketId, int attendeeId, int qty) throws EMSException {
        Ticket t = tickets.get(ticketId);
        if (t == null) throw new NotFoundException("Ticket not found: " + ticketId);
        if (qty > t.available()) throw new SoldOutException("Only " + t.available() + " ticket(s) left.");
        t.setSold(t.getSold() + qty);
        Purchase p = new Purchase(purchaseSeq.getAndIncrement(), ticketId, t.getEventId(), attendeeId, qty,
                t.getPrice() * qty, LocalDateTime.now());
        purchases.add(p);
        return p;
    }
    @Override public synchronized List<Purchase> purchases() { return new ArrayList<>(purchases); }

    @Override public synchronized void addNotification(Notification n) {
        notifications.add(new Notification(noteSeq.getAndIncrement(), n.userId(), n.message(), n.createdAt()));
    }
    @Override public synchronized List<Notification> notificationsFor(int userId) {
        return notifications.stream().filter(n -> n.userId() == userId).collect(Collectors.toList());
    }

    @Override public synchronized String getSetting(String key, String def) { return settings.getOrDefault(key, def); }
    @Override public synchronized void setSetting(String key, String value) { settings.put(key, value); }
    @Override public synchronized Map<String, String> settings() { return new TreeMap<>(settings); }
}

/**
 * JDBC store (MySQL / SQLite / any JDBC database).
 * Demonstrates: schema design, CRUD with PreparedStatement, transactions.
 * The matching JDBC driver jar must be on the classpath.
 */
class JdbcStore implements DataStore {
    private interface RowMapper<T> { T map(ResultSet rs) throws SQLException; }

    private final Connection con;
    private final boolean mysql;

    JdbcStore(String url, String user, String password) throws EMSException {
        try {
            this.con = (user == null || user.isEmpty()) ? DriverManager.getConnection(url)
                                                         : DriverManager.getConnection(url, user, password);
            this.mysql = url.startsWith("jdbc:mysql") || url.startsWith("jdbc:mariadb");
            if (url.startsWith("jdbc:sqlite")) { try (Statement st = con.createStatement()) { st.execute("PRAGMA foreign_keys = ON"); } }
            createSchema();
        } catch (SQLException e) {
            throw new EMSException("Cannot connect to database (is the JDBC driver on the classpath?): " + e.getMessage(), e);
        }
    }

    /* ---------------- schema design ---------------- */
    private void createSchema() throws SQLException {
        String pk = mysql ? "INT PRIMARY KEY AUTO_INCREMENT" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        String[] ddl = {
            "CREATE TABLE IF NOT EXISTS users (id " + pk + ", name VARCHAR(100) NOT NULL, email VARCHAR(150) NOT NULL UNIQUE, "
                + "password VARCHAR(64) NOT NULL, role VARCHAR(20) NOT NULL, city VARCHAR(100), preferences VARCHAR(255))",
            "CREATE TABLE IF NOT EXISTS events (id " + pk + ", organizer_id INT NOT NULL, title VARCHAR(150) NOT NULL, "
                + "description VARCHAR(1000), event_date VARCHAR(10) NOT NULL, event_time VARCHAR(8) NOT NULL, "
                + "venue VARCHAR(150) NOT NULL, status VARCHAR(20) NOT NULL, "
                + "FOREIGN KEY (organizer_id) REFERENCES users(id) ON DELETE CASCADE)",
            "CREATE TABLE IF NOT EXISTS tickets (id " + pk + ", event_id INT NOT NULL, price DOUBLE NOT NULL, "
                + "quantity INT NOT NULL, sold INT NOT NULL DEFAULT 0, "
                + "FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE)",
            "CREATE TABLE IF NOT EXISTS registrations (id " + pk + ", event_id INT NOT NULL, attendee_id INT NOT NULL, "
                + "registered_at VARCHAR(40) NOT NULL, status VARCHAR(20) NOT NULL, UNIQUE (event_id, attendee_id), "
                + "FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE, "
                + "FOREIGN KEY (attendee_id) REFERENCES users(id) ON DELETE CASCADE)",
            "CREATE TABLE IF NOT EXISTS purchases (id " + pk + ", ticket_id INT NOT NULL, event_id INT NOT NULL, "
                + "attendee_id INT NOT NULL, quantity INT NOT NULL, amount DOUBLE NOT NULL, purchased_at VARCHAR(40) NOT NULL, "
                + "FOREIGN KEY (ticket_id) REFERENCES tickets(id) ON DELETE CASCADE, "
                + "FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE, "
                + "FOREIGN KEY (attendee_id) REFERENCES users(id) ON DELETE CASCADE)",
            "CREATE TABLE IF NOT EXISTS notifications (id " + pk + ", user_id INT NOT NULL, message VARCHAR(1000) NOT NULL, "
                + "created_at VARCHAR(40) NOT NULL, FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE)",
            "CREATE TABLE IF NOT EXISTS settings (k VARCHAR(100) PRIMARY KEY, v VARCHAR(255) NOT NULL)"
        };
        try (Statement st = con.createStatement()) { for (String sql : ddl) st.execute(sql); }
    }

    /* ---------------- tiny JDBC helpers ---------------- */
    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
    }
    private <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) throws EMSException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(mapper.map(rs));
                return out;
            }
        } catch (SQLException e) { throw new EMSException("Database error: " + e.getMessage(), e); }
    }
    private int update(String sql, Object... params) throws EMSException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) { throw new EMSException("Database error: " + e.getMessage(), e); }
    }
    private int insert(String sql, Object... params) throws EMSException {
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, params);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) { return keys.next() ? keys.getInt(1) : 0; }
        } catch (SQLException e) { throw new EMSException("Database error: " + e.getMessage(), e); }
    }

    /* ---------------- row mappers ---------------- */
    private static User mapUser(ResultSet rs) throws SQLException {
        User u = User.create(rs.getInt("id"), rs.getString("name"), rs.getString("email"),
                rs.getString("password"), Role.valueOf(rs.getString("role")));
        u.setCity(rs.getString("city")); u.setPreferences(rs.getString("preferences"));
        return u;
    }
    private static Event mapEvent(ResultSet rs) throws SQLException {
        return new Event(rs.getInt("id"), rs.getInt("organizer_id"), rs.getString("title"), rs.getString("description"),
                LocalDate.parse(rs.getString("event_date")), LocalTime.parse(rs.getString("event_time")),
                rs.getString("venue"), EventStatus.valueOf(rs.getString("status")));
    }
    private static Ticket mapTicket(ResultSet rs) throws SQLException {
        return new Ticket(rs.getInt("id"), rs.getInt("event_id"), rs.getDouble("price"), rs.getInt("quantity"), rs.getInt("sold"));
    }
    private static Registration mapReg(ResultSet rs) throws SQLException {
        return new Registration(rs.getInt("id"), rs.getInt("event_id"), rs.getInt("attendee_id"),
                LocalDateTime.parse(rs.getString("registered_at")), rs.getString("status"));
    }
    private static Purchase mapPurchase(ResultSet rs) throws SQLException {
        return new Purchase(rs.getInt("id"), rs.getInt("ticket_id"), rs.getInt("event_id"), rs.getInt("attendee_id"),
                rs.getInt("quantity"), rs.getDouble("amount"), LocalDateTime.parse(rs.getString("purchased_at")));
    }

    /* ---------------- users ---------------- */
    @Override public synchronized User addUser(User u) throws EMSException {
        if (findUserByEmail(u.getEmail()).isPresent()) throw new ValidationException("Email already registered: " + u.getEmail());
        u.setId(insert("INSERT INTO users(name,email,password,role,city,preferences) VALUES(?,?,?,?,?,?)",
                u.getName(), u.getEmail(), u.getPassword(), u.getRole().name(), u.getCity(), u.getPreferences()));
        return u;
    }
    @Override public synchronized void updateUser(User u) throws EMSException {
        Optional<User> clash = findUserByEmail(u.getEmail());
        if (clash.isPresent() && clash.get().getId() != u.getId()) throw new ValidationException("Email already in use: " + u.getEmail());
        int rows = update("UPDATE users SET name=?, email=?, password=?, role=?, city=?, preferences=? WHERE id=?",
                u.getName(), u.getEmail(), u.getPassword(), u.getRole().name(), u.getCity(), u.getPreferences(), u.getId());
        if (rows == 0) throw new NotFoundException("User not found: " + u.getId());
    }
    @Override public synchronized void deleteUser(int id) throws EMSException {
        if (update("DELETE FROM users WHERE id=?", id) == 0) throw new NotFoundException("User not found: " + id);
    }
    @Override public synchronized Optional<User> findUser(int id) throws EMSException {
        return query("SELECT * FROM users WHERE id=?", JdbcStore::mapUser, id).stream().findFirst();
    }
    @Override public synchronized Optional<User> findUserByEmail(String email) throws EMSException {
        return query("SELECT * FROM users WHERE LOWER(email)=LOWER(?)", JdbcStore::mapUser, email).stream().findFirst();
    }
    @Override public synchronized List<User> users() throws EMSException { return query("SELECT * FROM users ORDER BY id", JdbcStore::mapUser); }

    /* ---------------- events ---------------- */
    @Override public synchronized Event addEvent(Event e) throws EMSException {
        e.setId(insert("INSERT INTO events(organizer_id,title,description,event_date,event_time,venue,status) VALUES(?,?,?,?,?,?,?)",
                e.getOrganizerId(), e.getTitle(), e.getDescription(), e.getDate().toString(), e.getTime().toString(),
                e.getVenue(), e.getStatus().name()));
        return e;
    }
    @Override public synchronized void updateEvent(Event e) throws EMSException {
        int rows = update("UPDATE events SET title=?, description=?, event_date=?, event_time=?, venue=?, status=? WHERE id=?",
                e.getTitle(), e.getDescription(), e.getDate().toString(), e.getTime().toString(), e.getVenue(),
                e.getStatus().name(), e.getId());
        if (rows == 0) throw new NotFoundException("Event not found: " + e.getId());
    }
    @Override public synchronized void deleteEvent(int id) throws EMSException {
        if (update("DELETE FROM events WHERE id=?", id) == 0) throw new NotFoundException("Event not found: " + id);
    }
    @Override public synchronized Optional<Event> findEvent(int id) throws EMSException {
        return query("SELECT * FROM events WHERE id=?", JdbcStore::mapEvent, id).stream().findFirst();
    }
    @Override public synchronized List<Event> events() throws EMSException { return query("SELECT * FROM events ORDER BY id", JdbcStore::mapEvent); }

    /* ---------------- tickets ---------------- */
    @Override public synchronized Ticket addTicket(Ticket t) throws EMSException {
        t.setId(insert("INSERT INTO tickets(event_id,price,quantity,sold) VALUES(?,?,?,?)",
                t.getEventId(), t.getPrice(), t.getQuantity(), t.getSold()));
        return t;
    }
    @Override public synchronized void updateTicket(Ticket t) throws EMSException {
        if (update("UPDATE tickets SET price=?, quantity=?, sold=? WHERE id=?", t.getPrice(), t.getQuantity(), t.getSold(), t.getId()) == 0)
            throw new NotFoundException("Ticket not found: " + t.getId());
    }
    @Override public synchronized Optional<Ticket> findTicket(int id) throws EMSException {
        return query("SELECT * FROM tickets WHERE id=?", JdbcStore::mapTicket, id).stream().findFirst();
    }
    @Override public synchronized List<Ticket> ticketsForEvent(int eventId) throws EMSException {
        return query("SELECT * FROM tickets WHERE event_id=? ORDER BY id", JdbcStore::mapTicket, eventId);
    }

    /* ---------------- registrations ---------------- */
    @Override public synchronized Registration addRegistration(Registration r) throws EMSException {
        boolean dup = !query("SELECT id FROM registrations WHERE event_id=? AND attendee_id=?", rs -> rs.getInt(1),
                r.eventId(), r.attendeeId()).isEmpty();
        if (dup) throw new ValidationException("Already registered/subscribed for this event.");
        int id = insert("INSERT INTO registrations(event_id,attendee_id,registered_at,status) VALUES(?,?,?,?)",
                r.eventId(), r.attendeeId(), r.registeredAt().toString(), r.status());
        return new Registration(id, r.eventId(), r.attendeeId(), r.registeredAt(), r.status());
    }
    @Override public synchronized List<Registration> registrations() throws EMSException {
        return query("SELECT * FROM registrations ORDER BY id", JdbcStore::mapReg);
    }

    /** TRANSACTION: reserve stock + insert purchase, commit together or roll back together. */
    @Override public synchronized Purchase purchase(int ticketId, int attendeeId, int qty) throws EMSException {
        try {
            con.setAutoCommit(false);
            try {
                Ticket t = findTicket(ticketId).orElseThrow(() -> new NotFoundException("Ticket not found: " + ticketId));
                int rows = update("UPDATE tickets SET sold = sold + ? WHERE id = ? AND quantity - sold >= ?", qty, ticketId, qty);
                if (rows == 0) throw new SoldOutException("Not enough tickets left (requested " + qty + ").");
                double amount = t.getPrice() * qty;
                LocalDateTime now = LocalDateTime.now();
                int id = insert("INSERT INTO purchases(ticket_id,event_id,attendee_id,quantity,amount,purchased_at) VALUES(?,?,?,?,?,?)",
                        ticketId, t.getEventId(), attendeeId, qty, amount, now.toString());
                con.commit();
                return new Purchase(id, ticketId, t.getEventId(), attendeeId, qty, amount, now);
            } catch (EMSException | RuntimeException ex) {
                con.rollback();
                throw ex;
            } finally {
                con.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new EMSException("Transaction failed: " + e.getMessage(), e);
        }
    }
    @Override public synchronized List<Purchase> purchases() throws EMSException {
        return query("SELECT * FROM purchases ORDER BY id", JdbcStore::mapPurchase);
    }

    /* ---------------- notifications & settings ---------------- */
    @Override public synchronized void addNotification(Notification n) throws EMSException {
        insert("INSERT INTO notifications(user_id,message,created_at) VALUES(?,?,?)", n.userId(), n.message(), n.createdAt().toString());
    }
    @Override public synchronized List<Notification> notificationsFor(int userId) throws EMSException {
        return query("SELECT * FROM notifications WHERE user_id=? ORDER BY id",
                rs -> new Notification(rs.getInt("id"), rs.getInt("user_id"), rs.getString("message"),
                        LocalDateTime.parse(rs.getString("created_at"))), userId);
    }
    @Override public synchronized String getSetting(String key, String def) throws EMSException {
        return query("SELECT v FROM settings WHERE k=?", rs -> rs.getString(1), key).stream().findFirst().orElse(def);
    }
    @Override public synchronized void setSetting(String key, String value) throws EMSException {
        if (update("UPDATE settings SET v=? WHERE k=?", value, key) == 0) update("INSERT INTO settings(k,v) VALUES(?,?)", key, value);
    }
    @Override public synchronized Map<String, String> settings() throws EMSException {
        Map<String, String> m = new TreeMap<>();
        for (String[] kv : query("SELECT k, v FROM settings", rs -> new String[]{rs.getString(1), rs.getString(2)})) m.put(kv[0], kv[1]);
        return m;
    }
}

/* ========================================================================== */
/*  5. SERVICES (business logic)                                              */
/* ========================================================================== */

/** Background delivery of notifications using a single worker thread. */
class NotificationService {
    private final DataStore store;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notification-worker");
        t.setDaemon(true);
        return t;
    });

    NotificationService(DataStore store) { this.store = store; }

    void notifyUser(int userId, String message) {
        worker.submit(() -> {
            try {
                store.addNotification(new Notification(0, userId, message, LocalDateTime.now()));
                ActivityLog.record("Notification delivered to user #" + userId);
            } catch (EMSException e) {
                ActivityLog.record("Notification FAILED for user #" + userId + ": " + e.getMessage());
            }
        });
    }
    /** Block until every queued notification has been delivered. */
    void flush() {
        try { worker.submit(() -> { }).get(5, TimeUnit.SECONDS); }
        catch (Exception ignored) { Thread.currentThread().interrupt(); }
    }
}

class UserService {
    private final DataStore store;
    UserService(DataStore store) { this.store = store; }

    static void requireRole(User actor, Role role) throws AccessDeniedException {
        if (actor == null || actor.getRole() != role) throw new AccessDeniedException("Only " + role + " can perform this action.");
    }

    /** Public sign-up: Admin accounts can only be created by an existing Admin. */
    User selfRegister(String name, String email, String password, Role role, String city) throws EMSException {
        if (role == Role.ADMIN) throw new AccessDeniedException("Admin accounts cannot be self-registered.");
        return createAccount(name, email, password, role, city);
    }
    User createAccount(String name, String email, String password, Role role, String city) throws EMSException {
        Validator.required(name, "Name");
        if (password == null || password.length() < 6) throw new ValidationException("Password must be at least 6 characters.");
        User u = User.create(0, name.trim(), Validator.email(email), Security.hash(password), role);
        u.setCity(city);
        store.addUser(u);
        ActivityLog.record("User created: " + u);
        return u;
    }
    User login(String email, String password) throws EMSException {
        User u = store.findUserByEmail(email == null ? "" : email.trim())
                .orElseThrow(() -> new AuthException("Invalid email or password."));
        if (!u.getPassword().equals(Security.hash(password == null ? "" : password))) throw new AuthException("Invalid email or password.");
        ActivityLog.record("Login: " + u.getEmail());
        return u;
    }
    void updateProfile(User actor, String name, String city, String preferences) throws EMSException {
        if (name != null && !name.isBlank()) actor.setName(name.trim());
        if (city != null && !city.isBlank()) actor.setCity(city.trim());
        if (preferences != null && !preferences.isBlank()) actor.setPreferences(preferences.trim());
        store.updateUser(actor);
        ActivityLog.record("Profile updated: " + actor.getEmail());
    }
}

class AdminService {
    private static final Set<String> NUMERIC_SETTINGS = Set.of("max_tickets_per_purchase", "session_timeout_minutes");
    private static final Set<String> ALL_SETTINGS = Set.of("system_name", "auto_approve_events",
            "max_tickets_per_purchase", "session_timeout_minutes");

    private final DataStore store;
    private final UserService users;
    private final NotificationService notifier;

    AdminService(DataStore store, UserService users, NotificationService notifier) {
        this.store = store; this.users = users; this.notifier = notifier;
    }

    /* ---- user management ---- */
    List<User> listUsers(User actor) throws EMSException { UserService.requireRole(actor, Role.ADMIN); return store.users(); }

    String createUser(User actor, String name, String email, String password, Role role, String city) throws EMSException {
        UserService.requireRole(actor, Role.ADMIN);
        User u = users.createAccount(name, email, password, role, city);
        return "User created successfully: " + u;
    }
    String updateUser(User actor, int id, String name, String email, Role role) throws EMSException {
        UserService.requireRole(actor, Role.ADMIN);
        User old = store.findUser(id).orElseThrow(() -> new NotFoundException("User not found: " + id));
        User updated = User.create(id, name == null || name.isBlank() ? old.getName() : name.trim(),
                email == null || email.isBlank() ? old.getEmail() : Validator.email(email),
                old.getPassword(), role == null ? old.getRole() : role);
        updated.setCity(old.getCity()); updated.setPreferences(old.getPreferences());
        store.updateUser(updated);
        ActivityLog.record("Admin " + actor.getEmail() + " updated user #" + id);
        return "User updated successfully: " + updated;
    }
    String deleteUser(User actor, int id) throws EMSException {
        UserService.requireRole(actor, Role.ADMIN);
        if (actor.getId() == id) throw new ValidationException("You cannot delete your own account.");
        store.deleteUser(id);
        ActivityLog.record("Admin " + actor.getEmail() + " deleted user #" + id);
        return "User #" + id + " deleted successfully.";
    }

    /* ---- event approvals ---- */
    List<Event> pendingEvents(User actor) throws EMSException {
        UserService.requireRole(actor, Role.ADMIN);
        return store.events().stream().filter(e -> e.getStatus() == EventStatus.PENDING).collect(Collectors.toList());
    }
    String decide(User actor, int eventId, boolean approve) throws EMSException {
        UserService.requireRole(actor, Role.ADMIN);
        Event e = store.findEvent(eventId).orElseThrow(() -> new NotFoundException("Event not found: " + eventId));
        e.setStatus(approve ? EventStatus.APPROVED : EventStatus.REJECTED);
        store.updateEvent(e);
        notifier.notifyUser(e.getOrganizerId(), "Your event '" + e.getTitle() + "' was " + e.getStatus() + " by the admin.");
        ActivityLog.record("Event #" + eventId + " " + e.getStatus() + " by " + actor.getEmail());
        return "Event #" + eventId + " status: " + e.getStatus();
    }

    /* ---- system settings ---- */
    Map<String, String> settings(User actor) throws EMSException { UserService.requireRole(actor, Role.ADMIN); return store.settings(); }
    String updateSetting(User actor, String key, String value) throws EMSException {
        UserService.requireRole(actor, Role.ADMIN);
        key = Validator.required(key, "Setting key");
        value = Validator.required(value, "Setting value");
        if (!ALL_SETTINGS.contains(key)) throw new ValidationException("Unknown setting. Valid keys: " + ALL_SETTINGS);
        if (NUMERIC_SETTINGS.contains(key)) {
            try { if (Integer.parseInt(value) <= 0) throw new NumberFormatException(); }
            catch (NumberFormatException e) { throw new ValidationException(key + " must be a positive number."); }
        }
        if (key.equals("auto_approve_events") && !value.equals("true") && !value.equals("false"))
            throw new ValidationException("auto_approve_events must be true or false.");
        store.setSetting(key, value);
        ActivityLog.record("Setting changed: " + key + "=" + value);
        return "Settings updated successfully: " + key + " = " + value;
    }
}

class OrganizerService {
    private final DataStore store;
    private final NotificationService notifier;

    OrganizerService(DataStore store, NotificationService notifier) { this.store = store; this.notifier = notifier; }

    Event createEvent(User actor, String title, String description, LocalDate date, LocalTime time, String venue) throws EMSException {
        UserService.requireRole(actor, Role.ORGANIZER);
        Validator.required(title, "Title"); Validator.required(venue, "Venue");
        if (date.isBefore(LocalDate.now())) throw new ValidationException("Event date cannot be in the past.");
        boolean auto = Boolean.parseBoolean(store.getSetting("auto_approve_events", "false"));
        Event e = store.addEvent(new Event(0, actor.getId(), title.trim(), description == null ? "" : description.trim(),
                date, time, venue.trim(), auto ? EventStatus.APPROVED : EventStatus.PENDING));
        ActivityLog.record("Event created: #" + e.getId() + " by " + actor.getEmail());
        return e;
    }
    private Event owned(User actor, int eventId) throws EMSException {
        UserService.requireRole(actor, Role.ORGANIZER);
        Event e = store.findEvent(eventId).orElseThrow(() -> new NotFoundException("Event not found: " + eventId));
        if (e.getOrganizerId() != actor.getId()) throw new AccessDeniedException("You do not own event #" + eventId);
        return e;
    }
    /** Blank / null arguments mean "keep current value". */
    String updateEvent(User actor, int eventId, String title, String description, LocalDate date, LocalTime time, String venue) throws EMSException {
        Event e = owned(actor, eventId);
        if (title != null && !title.isBlank()) e.setTitle(title.trim());
        if (description != null && !description.isBlank()) e.setDescription(description.trim());
        if (date != null) { if (date.isBefore(LocalDate.now())) throw new ValidationException("Date cannot be in the past."); e.setDate(date); }
        if (time != null) e.setTime(time);
        if (venue != null && !venue.isBlank()) e.setVenue(venue.trim());
        if (e.getStatus() == EventStatus.REJECTED) e.setStatus(EventStatus.PENDING);   // re-submit for approval
        store.updateEvent(e);
        sendMessage(actor, eventId, "Event updated: '" + e.getTitle() + "' now on " + e.getDate() + " " + e.getTime() + " at " + e.getVenue());
        return "Event #" + eventId + " updated successfully.";
    }
    String deleteEvent(User actor, int eventId) throws EMSException {
        Event e = owned(actor, eventId);
        sendMessage(actor, eventId, "Event cancelled: '" + e.getTitle() + "'");
        store.deleteEvent(eventId);
        ActivityLog.record("Event deleted: #" + eventId);
        return "Event #" + eventId + " deleted successfully.";
    }
    List<Event> myEvents(User actor) throws EMSException {
        UserService.requireRole(actor, Role.ORGANIZER);
        return store.events().stream().filter(e -> e.getOrganizerId() == actor.getId()).collect(Collectors.toList());
    }
    List<Event> upcoming(User actor) throws EMSException {
        return myEvents(actor).stream().filter(Event::isUpcoming)
                .sorted((a, b) -> a.getDate().compareTo(b.getDate())).collect(Collectors.toList());
    }

    /* ---- tickets ---- */
    Ticket createTicket(User actor, int eventId, double price, int quantity) throws EMSException {
        owned(actor, eventId);
        Validator.positive(price, "Price");
        if (quantity <= 0) throw new ValidationException("Quantity must be greater than zero.");
        Ticket t = store.addTicket(new Ticket(0, eventId, price, quantity, 0));
        ActivityLog.record("Ticket #" + t.getId() + " created for event #" + eventId);
        return t;
    }
    String updateTicket(User actor, int ticketId, double price, int quantity) throws EMSException {
        Ticket t = store.findTicket(ticketId).orElseThrow(() -> new NotFoundException("Ticket not found: " + ticketId));
        owned(actor, t.getEventId());
        Validator.positive(price, "Price");
        if (quantity < t.getSold()) throw new ValidationException("Quantity cannot be lower than tickets already sold (" + t.getSold() + ").");
        t.setPrice(price); t.setQuantity(quantity);
        store.updateTicket(t);
        return "Ticket #" + ticketId + " updated successfully.";
    }
    List<Ticket> ticketsFor(User actor, int eventId) throws EMSException { owned(actor, eventId); return store.ticketsForEvent(eventId); }

    /* ---- communication ---- */
    int sendMessage(User actor, int eventId, String message) throws EMSException {
        Event e = owned(actor, eventId);
        Validator.required(message, "Message");
        List<Registration> targets = store.registrations().stream().filter(r -> r.eventId() == eventId).toList();
        for (Registration r : targets) notifier.notifyUser(r.attendeeId(), "[" + e.getTitle() + "] " + message.trim());
        ActivityLog.record("Organizer " + actor.getEmail() + " messaged " + targets.size() + " attendee(s) of event #" + eventId);
        return targets.size();
    }
}

/** Payment abstraction (interface) with a mock implementation. */
interface PaymentProcessor {
    String charge(String cardNumber, String expiry, double amount) throws ValidationException;
    void refund(String reference);
}

class MockPaymentProcessor implements PaymentProcessor {
    private static final Pattern EXPIRY = Pattern.compile("^(0[1-9]|1[0-2])/\\d{2}$");
    @Override public String charge(String cardNumber, String expiry, double amount) throws ValidationException {
        String digits = cardNumber == null ? "" : cardNumber.replaceAll("[\\s-]", "");
        if (!digits.matches("\\d{12,19}") || !Validator.luhn(digits)) throw new ValidationException("Invalid card number.");
        if (expiry == null || !EXPIRY.matcher(expiry.trim()).matches()) throw new ValidationException("Expiry must be in MM/YY format.");
        String ref = "PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        ActivityLog.record(String.format("Payment %s charged %.2f", ref, amount));
        return ref;
    }
    @Override public void refund(String reference) { ActivityLog.record("Payment " + reference + " refunded"); }
}

class AttendeeService {
    private final DataStore store;
    private final NotificationService notifier;
    private final PaymentProcessor payment;
    private final UserService users;

    AttendeeService(DataStore store, NotificationService notifier, PaymentProcessor payment, UserService users) {
        this.store = store; this.notifier = notifier; this.payment = payment; this.users = users;
    }

    List<Event> browse() throws EMSException {
        return store.events().stream().filter(e -> e.getStatus() == EventStatus.APPROVED && e.isUpcoming())
                .sorted((a, b) -> a.getDate().compareTo(b.getDate())).collect(Collectors.toList());
    }
    List<Ticket> ticketsFor(int eventId) throws EMSException { return store.ticketsForEvent(eventId); }

    private Event approvedEvent(int eventId) throws EMSException {
        Event e = store.findEvent(eventId).orElseThrow(() -> new NotFoundException("Event not found: " + eventId));
        if (e.getStatus() != EventStatus.APPROVED) throw new ValidationException("Event is not open for registration.");
        return e;
    }
    String register(User actor, int eventId) throws EMSException {
        UserService.requireRole(actor, Role.ATTENDEE);
        Event e = approvedEvent(eventId);
        store.addRegistration(new Registration(0, eventId, actor.getId(), LocalDateTime.now(), "REGISTERED"));
        notifier.notifyUser(actor.getId(), "You are registered for '" + e.getTitle() + "' on " + e.getDate() + ".");
        ActivityLog.record(actor.getEmail() + " registered for event #" + eventId);
        return "Registration confirmed for '" + e.getTitle() + "'.";
    }
    String subscribe(User actor, int eventId) throws EMSException {
        UserService.requireRole(actor, Role.ATTENDEE);
        Event e = approvedEvent(eventId);
        store.addRegistration(new Registration(0, eventId, actor.getId(), LocalDateTime.now(), "SUBSCRIBED"));
        return "Subscribed to updates for '" + e.getTitle() + "'.";
    }

    /** Charge -> reserve (atomic) -> auto-register. If reservation fails the payment is refunded. */
    Purchase purchaseTicket(User actor, int ticketId, int qty, String card, String expiry) throws EMSException {
        UserService.requireRole(actor, Role.ATTENDEE);
        int max = Integer.parseInt(store.getSetting("max_tickets_per_purchase", "10"));
        if (qty < 1 || qty > max) throw new ValidationException("You can buy between 1 and " + max + " tickets at a time.");
        Ticket t = store.findTicket(ticketId).orElseThrow(() -> new NotFoundException("Ticket not found: " + ticketId));
        Event e = approvedEvent(t.getEventId());
        String ref = payment.charge(card, expiry, t.getPrice() * qty);
        Purchase p;
        try {
            p = store.purchase(ticketId, actor.getId(), qty);
        } catch (EMSException ex) {
            payment.refund(ref);
            throw ex;
        }
        try { store.addRegistration(new Registration(0, e.getId(), actor.getId(), LocalDateTime.now(), "REGISTERED")); }
        catch (ValidationException alreadyRegistered) { /* fine - keep existing registration */ }
        notifier.notifyUser(actor.getId(), String.format("Purchase confirmed: %d x ticket for '%s' (%.2f). Ref %s", qty, e.getTitle(), p.amount(), ref));
        ActivityLog.record(actor.getEmail() + " bought " + qty + " ticket(s) for event #" + e.getId());
        return p;
    }
    List<Purchase> myPurchases(User actor) throws EMSException {
        return store.purchases().stream().filter(p -> p.attendeeId() == actor.getId()).collect(Collectors.toList());
    }
    List<Registration> history(User actor) throws EMSException {
        return store.registrations().stream().filter(r -> r.attendeeId() == actor.getId()).collect(Collectors.toList());
    }
    List<Event> myUpcoming(User actor) throws EMSException {
        List<Event> out = new ArrayList<>();
        for (Registration r : history(actor)) {
            Optional<Event> e = store.findEvent(r.eventId());
            if (e.isPresent() && e.get().isUpcoming()) out.add(e.get());
        }
        return out;
    }
    List<Notification> updates(User actor) throws EMSException { return store.notificationsFor(actor.getId()); }
    String updateProfile(User actor, String name, String city, String prefs) throws EMSException {
        users.updateProfile(actor, name, city, prefs);
        return "Profile updated successfully.";
    }
}

/** Statistics: text tables + ASCII bar charts. */
class StatsService {
    private final DataStore store;
    StatsService(DataStore store) { this.store = store; }

    private static String bar(int value, int max) {
        int len = max == 0 ? 0 : (int) Math.round(30.0 * value / max);
        return "#".repeat(len) + " " + value;
    }

    String systemReport() throws EMSException {
        List<Event> events = store.events();
        List<Purchase> purchases = store.purchases();
        StringBuilder sb = new StringBuilder("=== SYSTEM STATISTICS ===\n");
        sb.append("Users          : ").append(store.users().size()).append('\n');
        sb.append("Events         : ").append(events.size()).append("  (");
        for (EventStatus s : EventStatus.values())
            sb.append(s).append('=').append(events.stream().filter(e -> e.getStatus() == s).count()).append(' ');
        sb.append(")\nRegistrations  : ").append(store.registrations().size()).append('\n');
        sb.append("Tickets sold   : ").append(purchases.stream().mapToInt(Purchase::quantity).sum()).append('\n');
        sb.append(String.format("Revenue        : %.2f%n", purchases.stream().mapToDouble(Purchase::amount).sum()));
        sb.append("\nRegistrations per event\n").append(perEventChart(events));
        return sb.toString();
    }
    private String perEventChart(List<Event> events) throws EMSException {
        List<Registration> regs = store.registrations();
        Map<Event, Integer> counts = new LinkedHashMap<>();
        for (Event e : events) counts.put(e, (int) regs.stream().filter(r -> r.eventId() == e.getId()).count());
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        StringBuilder sb = new StringBuilder();
        counts.forEach((e, c) -> sb.append(String.format("  %-24.24s %s%n", e.getTitle(), bar(c, max))));
        return sb.toString();
    }

    String organizerReport(User organizer) throws EMSException {
        List<Event> mine = store.events().stream().filter(e -> e.getOrganizerId() == organizer.getId()).toList();
        List<Registration> regs = store.registrations();
        List<Purchase> purchases = store.purchases();
        StringBuilder sb = new StringBuilder("=== MY EVENT STATISTICS ===\n");
        sb.append(String.format("%-26s %6s %8s %10s%n", "Event", "Regs", "Tickets", "Revenue"));
        int maxSold = 0;
        Map<String, Integer> demographics = new TreeMap<>();
        for (Event e : mine) {
            long r = regs.stream().filter(x -> x.eventId() == e.getId()).count();
            int sold = purchases.stream().filter(p -> p.eventId() == e.getId()).mapToInt(Purchase::quantity).sum();
            double rev = purchases.stream().filter(p -> p.eventId() == e.getId()).mapToDouble(Purchase::amount).sum();
            maxSold = Math.max(maxSold, sold);
            sb.append(String.format("%-26.26s %6d %8d %10.2f%n", e.getTitle(), r, sold, rev));
            for (Registration reg : regs) {
                if (reg.eventId() != e.getId()) continue;
                Optional<User> u = store.findUser(reg.attendeeId());
                String city = u.map(User::getCity).filter(c -> !c.isBlank()).orElse("Unknown");
                demographics.merge(city, 1, Integer::sum);
            }
        }
        sb.append("\nTicket sales\n");
        for (Event e : mine) {
            int sold = purchases.stream().filter(p -> p.eventId() == e.getId()).mapToInt(Purchase::quantity).sum();
            sb.append(String.format("  %-24.24s %s%n", e.getTitle(), bar(sold, maxSold)));
        }
        sb.append("\nAttendee demographics (by city)\n");
        int maxCity = demographics.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        demographics.forEach((c, n) -> sb.append(String.format("  %-24.24s %s%n", c, bar(n, maxCity))));
        return sb.toString();
    }
}

/* ========================================================================== */
/*  6. WEB LAYER  (servlet-style request/response + session management)       */
/* ========================================================================== */

class SessionManager {
    record Session(User user, Instant created, Instant lastAccess) { }
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final DataStore store;
    SessionManager(DataStore store) { this.store = store; }

    String create(User u) {
        String token = UUID.randomUUID().toString();
        sessions.put(token, new Session(u, Instant.now(), Instant.now()));
        return token;
    }
    Optional<User> get(String token) throws EMSException {
        if (token == null) return Optional.empty();
        Session s = sessions.get(token);
        if (s == null) return Optional.empty();
        long timeoutMin = Long.parseLong(store.getSetting("session_timeout_minutes", "30"));
        if (Instant.now().isAfter(s.lastAccess().plusSeconds(timeoutMin * 60))) { sessions.remove(token); return Optional.empty(); }
        sessions.put(token, new Session(s.user(), s.created(), Instant.now()));
        return Optional.of(s.user());
    }
    void invalidate(String token) { if (token != null) sessions.remove(token); }
}

class WebServer {
    private final HttpServer server;
    private final SessionManager sessions;
    private final UserService users;
    private final AdminService admin;
    private final OrganizerService organizer;
    private final AttendeeService attendee;
    private final StatsService stats;

    WebServer(int port, DataStore store, UserService users, AdminService admin, OrganizerService organizer,
              AttendeeService attendee, StatsService stats) throws IOException {
        this.users = users; this.admin = admin; this.organizer = organizer; this.attendee = attendee; this.stats = stats;
        this.sessions = new SessionManager(store);
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));          // multi-threaded request handling
    }
    void start() { server.start(); }

    private void handle(HttpExchange ex) throws IOException {
        int status = 200;
        String body;
        try {
            body = route(ex);
        } catch (AuthException e) { status = 401; body = "ERROR: " + e.getMessage(); }
        catch (AccessDeniedException e) { status = 403; body = "ERROR: " + e.getMessage(); }
        catch (NotFoundException e) { status = 404; body = "ERROR: " + e.getMessage(); }
        catch (SoldOutException e) { status = 409; body = "ERROR: " + e.getMessage(); }
        catch (EMSException | IllegalArgumentException | DateTimeParseException e) { status = 400; body = "ERROR: " + e.getMessage(); }
        catch (Exception e) { status = 500; body = "ERROR: internal server error"; }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static Map<String, String> params(HttpExchange ex) throws IOException {
        String raw = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
        if (ex.getRequestMethod().equals("POST"))
            raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8) + (raw.isEmpty() ? "" : "&" + raw);
        Map<String, String> map = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            if (pair.isBlank()) continue;
            String[] kv = pair.split("=", 2);
            map.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return map;
    }
    private static String cookie(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Cookie");
        if (header == null) return null;
        for (String part : header.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals("SESSION")) return kv[1];
        }
        return null;
    }
    private User current(HttpExchange ex) throws EMSException {
        return sessions.get(cookie(ex)).orElseThrow(() -> new AuthException("Not logged in (or session expired). POST /api/login first."));
    }
    private static int intParam(Map<String, String> p, String key) throws ValidationException {
        try { return Integer.parseInt(p.getOrDefault(key, "").trim()); }
        catch (NumberFormatException e) { throw new ValidationException("Parameter '" + key + "' must be a number."); }
    }
    private static String join(List<?> items) { return items.stream().map(Object::toString).collect(Collectors.joining("\n")); }

    private String route(HttpExchange ex) throws Exception {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        Map<String, String> p = params(ex);
        String key = method + " " + path;

        switch (key) {
            case "GET /":
                return """
                        Online Event Management System - REST-style API (text responses)
                        POST /api/register           name,email,password,role(ORGANIZER|ATTENDEE),city
                        POST /api/login              email,password            -> sets SESSION cookie
                        POST /api/logout
                        GET  /api/events             list approved upcoming events (public)
                        GET  /api/dashboard          role based dashboard (login required)
                        POST /api/events/register    eventId                    (attendee)
                        POST /api/tickets/purchase   ticketId,qty,card,expiry   (attendee)
                        POST /api/organizer/events   title,description,date(yyyy-MM-dd),time(HH:mm),venue
                        POST /api/admin/approve      eventId                    (admin)
                        POST /api/admin/reject       eventId                    (admin)
                        """;
            case "POST /api/register": {
                User u = users.selfRegister(p.get("name"), p.get("email"), p.get("password"),
                        Role.valueOf(p.getOrDefault("role", "ATTENDEE").toUpperCase()), p.get("city"));
                return "Registered successfully: " + u;
            }
            case "POST /api/login": {
                User u = users.login(p.get("email"), p.get("password"));
                ex.getResponseHeaders().add("Set-Cookie", "SESSION=" + sessions.create(u) + "; Path=/; HttpOnly");
                return "Welcome " + u.getName() + " (" + u.getRole() + ")";
            }
            case "POST /api/logout":
                sessions.invalidate(cookie(ex));
                return "Logged out.";
            case "GET /api/events": {
                List<String> lines = new ArrayList<>();
                for (Event e : attendee.browse()) {
                    lines.add(e.toString());
                    for (Ticket t : attendee.ticketsFor(e.getId())) lines.add("      " + t);
                }
                return lines.isEmpty() ? "No upcoming events." : String.join("\n", lines);
            }
            case "GET /api/dashboard": {
                User u = current(ex);
                switch (u.getRole()) {
                    case ADMIN:
                        return u.dashboardTitle() + "\nPending approvals:\n" + join(admin.pendingEvents(u)) + "\n\n" + stats.systemReport()
                                + "\nRecent activity:\n" + String.join("\n", ActivityLog.recent(10));
                    case ORGANIZER:
                        return u.dashboardTitle() + "\nMy events:\n" + join(organizer.myEvents(u)) + "\n\n" + stats.organizerReport(u);
                    default:
                        return u.dashboardTitle() + "\nUpcoming (registered):\n" + join(attendee.myUpcoming(u))
                                + "\n\nUpdates:\n" + join(attendee.updates(u));
                }
            }
            case "POST /api/events/register":
                return attendee.register(current(ex), intParam(p, "eventId"));
            case "POST /api/tickets/purchase": {
                Purchase pur = attendee.purchaseTicket(current(ex), intParam(p, "ticketId"), intParam(p, "qty"), p.get("card"), p.get("expiry"));
                return String.format("Purchase confirmed. Purchase #%d, amount %.2f", pur.id(), pur.amount());
            }
            case "POST /api/organizer/events": {
                Event e = organizer.createEvent(current(ex), p.get("title"), p.get("description"),
                        LocalDate.parse(p.getOrDefault("date", "")), LocalTime.parse(p.getOrDefault("time", "")), p.get("venue"));
                return "Event created successfully (" + e.getStatus() + "): " + e;
            }
            case "POST /api/admin/approve":
                return admin.decide(current(ex), intParam(p, "eventId"), true);
            case "POST /api/admin/reject":
                return admin.decide(current(ex), intParam(p, "eventId"), false);
            default:
                throw new NotFoundException("No route for " + key);
        }
    }
}

/* ========================================================================== */
/*  7. CONSOLE UI                                                             */
/* ========================================================================== */

class ConsoleApp {
    @FunctionalInterface private interface Action { void run() throws Exception; }

    private final Scanner in = new Scanner(System.in);
    private final DataStore store;
    private final UserService users;
    private final AdminService admin;
    private final OrganizerService organizer;
    private final AttendeeService attendee;
    private final StatsService stats;
    private final NotificationService notifier;

    ConsoleApp(DataStore store, UserService users, AdminService admin, OrganizerService organizer,
               AttendeeService attendee, StatsService stats, NotificationService notifier) {
        this.store = store; this.users = users; this.admin = admin; this.organizer = organizer;
        this.attendee = attendee; this.stats = stats; this.notifier = notifier;
    }

    /* ---------- helpers ---------- */
    private String ask(String prompt) { System.out.print(prompt + ": "); return in.nextLine().trim(); }
    private int askInt(String prompt) { return Integer.parseInt(ask(prompt)); }
    private void say(String s) { System.out.println(s); }
    private void title(String s) { say("\n==== " + s + " ===="); }
    private void attempt(Action a) {
        try { a.run(); }
        catch (EMSException e) { say("  [!] " + e.getMessage()); }
        catch (DateTimeParseException e) { say("  [!] Invalid date/time. Use yyyy-MM-dd and HH:mm."); }
        catch (NumberFormatException e) { say("  [!] Please enter a valid number."); }
        catch (IllegalArgumentException e) { say("  [!] Invalid value: " + e.getMessage()); }
        catch (NoSuchElementException e) { throw e; }       // input closed -> propagate
        catch (Exception e) { say("  [!] Unexpected error: " + e); }
    }
    private void printEvents(List<Event> list) {
        if (list.isEmpty()) { say("  (none)"); return; }
        list.forEach(e -> say("  " + e));
    }
    private void pause() { ask("\nPress Enter to continue"); }

    /* ---------- main loop ---------- */
    void run() {
        say("""
                +--------------------------------------------------+
                |        ONLINE EVENT MANAGEMENT SYSTEM            |
                +--------------------------------------------------+""");
        try {
            while (true) {
                title("MAIN MENU");
                say("1. Login\n2. Register (Organizer / Attendee)\n3. Exit");
                String c = ask("Choose");
                if (c.equals("3")) { say("Goodbye!"); return; }
                if (c.equals("1")) attempt(this::login);
                else if (c.equals("2")) attempt(this::register);
                else say("  [!] Invalid choice.");
            }
        } catch (NoSuchElementException endOfInput) {
            say("\nInput closed. Goodbye!");
        }
    }

    private void register() throws Exception {
        title("REGISTER");
        String name = ask("Name"), email = ask("Email"), pass = ask("Password (min 6 chars)"), city = ask("City");
        Role role = ask("Role (1=Organizer, 2=Attendee)").equals("1") ? Role.ORGANIZER : Role.ATTENDEE;
        User u = users.selfRegister(name, email, pass, role, city);
        say("  Registration successful! You can now log in as " + u.getEmail());
    }

    private void login() throws Exception {
        title("LOGIN");
        User u = users.login(ask("Email"), ask("Password"));
        say("  Welcome, " + u.getName() + "!");
        switch (u.getRole()) {
            case ADMIN -> adminDashboard(u);
            case ORGANIZER -> organizerDashboard(u);
            case ATTENDEE -> attendeeDashboard(u);
        }
    }

    /* ---------- ADMIN ---------- */
    private void adminDashboard(User u) {
        while (true) {
            title(u.dashboardTitle());
            say("1. User Management\n2. Event Approvals\n3. System Settings\n4. Event Statistics\n5. System Activity Monitoring\n0. Logout");
            switch (ask("Choose")) {
                case "1" -> attempt(() -> adminUsers(u));
                case "2" -> attempt(() -> adminApprovals(u));
                case "3" -> attempt(() -> adminSettings(u));
                case "4" -> attempt(() -> say(stats.systemReport()));
                case "5" -> attempt(() -> ActivityLog.recent(15).forEach(this::say));
                case "0" -> { return; }
                default -> say("  [!] Invalid choice.");
            }
        }
    }
    private void adminUsers(User u) throws Exception {
        title("USER MANAGEMENT");
        say(String.format("  %-4s %-20s %-28s %-10s %s", "ID", "Name", "Email", "Role", "City"));
        for (User x : admin.listUsers(u)) say(String.format("  %-4d %-20s %-28s %-10s %s", x.getId(), x.getName(), x.getEmail(), x.getRole(), x.getCity()));
        say("\n1. Create user  2. Edit user  3. Delete user  0. Back");
        switch (ask("Choose")) {
            case "1" -> say("  " + admin.createUser(u, ask("Name"), ask("Email"), ask("Password"),
                    Role.valueOf(ask("Role (ADMIN/ORGANIZER/ATTENDEE)").toUpperCase()), ask("City")));
            case "2" -> {
                int id = askInt("User ID");
                String name = ask("New name (blank = keep)"), email = ask("New email (blank = keep)"), r = ask("New role (blank = keep)");
                say("  " + admin.updateUser(u, id, name, email, r.isBlank() ? null : Role.valueOf(r.toUpperCase())));
            }
            case "3" -> say("  " + admin.deleteUser(u, askInt("User ID")));
            default -> { }
        }
    }
    private void adminApprovals(User u) throws Exception {
        title("EVENT APPROVALS");
        List<Event> pending = admin.pendingEvents(u);
        printEvents(pending);
        if (pending.isEmpty()) return;
        say("1. Approve  2. Reject  0. Back");
        String c = ask("Choose");
        if (c.equals("1") || c.equals("2")) say("  " + admin.decide(u, askInt("Event ID"), c.equals("1")));
    }
    private void adminSettings(User u) throws Exception {
        title("SYSTEM SETTINGS");
        admin.settings(u).forEach((k, v) -> say("  " + k + " = " + v));
        if (ask("Change a setting? (y/n)").equalsIgnoreCase("y")) say("  " + admin.updateSetting(u, ask("Key"), ask("New value")));
    }

    /* ---------- ORGANIZER ---------- */
    private void organizerDashboard(User u) {
        attempt(() -> { notifier.flush(); say("  You have " + store.notificationsFor(u.getId()).size() + " notification(s)."); });
        while (true) {
            title(u.dashboardTitle());
            say("1. Event Management\n2. Ticket Management\n3. Attendee Communication\n4. Event Statistics\n5. Upcoming Events\n6. My Notifications\n0. Logout");
            switch (ask("Choose")) {
                case "1" -> attempt(() -> organizerEvents(u));
                case "2" -> attempt(() -> organizerTickets(u));
                case "3" -> attempt(() -> say("  Message delivered to " + organizer.sendMessage(u, askInt("Event ID"), ask("Message")) + " attendee(s)."));
                case "4" -> attempt(() -> say(stats.organizerReport(u)));
                case "5" -> attempt(() -> printEvents(organizer.upcoming(u)));
                case "6" -> attempt(() -> { notifier.flush(); store.notificationsFor(u.getId()).forEach(n -> say("  " + n.createdAt().toLocalDate() + " " + n.message())); });
                case "0" -> { return; }
                default -> say("  [!] Invalid choice.");
            }
        }
    }
    private void organizerEvents(User u) throws Exception {
        title("EVENT MANAGEMENT");
        printEvents(organizer.myEvents(u));
        say("\n1. Create  2. Update  3. Delete  0. Back");
        switch (ask("Choose")) {
            case "1" -> {
                Event e = organizer.createEvent(u, ask("Title"), ask("Description"), LocalDate.parse(ask("Date (yyyy-MM-dd)")),
                        LocalTime.parse(ask("Time (HH:mm)")), ask("Venue"));
                say("  Event created successfully (" + e.getStatus() + "): " + e);
            }
            case "2" -> {
                int id = askInt("Event ID");
                String t = ask("Title (blank = keep)"), d = ask("Description (blank = keep)"), dt = ask("Date yyyy-MM-dd (blank = keep)"),
                        tm = ask("Time HH:mm (blank = keep)"), v = ask("Venue (blank = keep)");
                say("  " + organizer.updateEvent(u, id, t, d, dt.isBlank() ? null : LocalDate.parse(dt), tm.isBlank() ? null : LocalTime.parse(tm), v));
            }
            case "3" -> say("  " + organizer.deleteEvent(u, askInt("Event ID")));
            default -> { }
        }
    }
    private void organizerTickets(User u) throws Exception {
        title("TICKET MANAGEMENT");
        for (Event e : organizer.myEvents(u)) {
            say("  " + e);
            for (Ticket t : organizer.ticketsFor(u, e.getId())) say("      " + t);
        }
        say("\n1. Create ticket  2. Update ticket  0. Back");
        switch (ask("Choose")) {
            case "1" -> say("  Ticket created successfully: " + organizer.createTicket(u, askInt("Event ID"),
                    Double.parseDouble(ask("Price")), askInt("Quantity")));
            case "2" -> say("  " + organizer.updateTicket(u, askInt("Ticket ID"), Double.parseDouble(ask("New price")), askInt("New quantity")));
            default -> { }
        }
    }

    /* ---------- ATTENDEE ---------- */
    private void attendeeDashboard(User u) {
        attempt(() -> { notifier.flush(); say("  You have " + attendee.updates(u).size() + " update(s)."); });
        while (true) {
            title(u.dashboardTitle());
            say("1. Browse / Register for Events\n2. Purchase Tickets\n3. Ticket Purchases\n4. Event Updates\n5. Upcoming Events\n6. Registration History\n7. Profile Management\n0. Logout");
            switch (ask("Choose")) {
                case "1" -> attempt(() -> attendeeBrowse(u));
                case "2" -> attempt(() -> attendeePurchase(u));
                case "3" -> attempt(() -> {
                    title("MY TICKET PURCHASES");
                    List<Purchase> list = attendee.myPurchases(u);
                    if (list.isEmpty()) say("  (none)");
                    for (Purchase p : list) say(String.format("  Purchase #%d | %s | qty %d | %.2f | %s", p.id(),
                            store.findEvent(p.eventId()).map(Event::getTitle).orElse("(deleted event)"), p.quantity(), p.amount(), p.purchasedAt().toLocalDate()));
                });
                case "4" -> attempt(() -> {
                    notifier.flush();
                    title("EVENT UPDATES");
                    List<Notification> list = attendee.updates(u);
                    if (list.isEmpty()) say("  (no updates)");
                    list.forEach(n -> say("  " + n.createdAt().toLocalDate() + " | " + n.message()));
                });
                case "5" -> attempt(() -> { title("MY UPCOMING EVENTS"); printEvents(attendee.myUpcoming(u)); });
                case "6" -> attempt(() -> {
                    title("REGISTRATION HISTORY");
                    List<Registration> list = attendee.history(u);
                    if (list.isEmpty()) say("  (none)");
                    for (Registration r : list) say(String.format("  %s | %-28s | %s", r.registeredAt().toLocalDate(),
                            store.findEvent(r.eventId()).map(Event::getTitle).orElse("(deleted event)"), r.status()));
                });
                case "7" -> attempt(() -> {
                    title("PROFILE");
                    say("  Name: " + u.getName() + " | Email: " + u.getEmail() + " | City: " + u.getCity() + " | Preferences: " + u.getPreferences());
                    say("  " + attendee.updateProfile(u, ask("New name (blank = keep)"), ask("New city (blank = keep)"), ask("Preferences e.g. music,tech (blank = keep)")));
                });
                case "0" -> { return; }
                default -> say("  [!] Invalid choice.");
            }
        }
    }
    private void attendeeBrowse(User u) throws Exception {
        title("UPCOMING APPROVED EVENTS");
        List<Event> list = attendee.browse();
        for (Event e : list) {
            say("  " + e);
            for (Ticket t : attendee.ticketsFor(e.getId())) say("      " + t);
        }
        if (list.isEmpty()) { say("  (none)"); return; }
        say("\n1. Register  2. Subscribe to updates only  0. Back");
        switch (ask("Choose")) {
            case "1" -> say("  " + attendee.register(u, askInt("Event ID")));
            case "2" -> say("  " + attendee.subscribe(u, askInt("Event ID")));
            default -> { }
        }
    }
    private void attendeePurchase(User u) throws Exception {
        title("PURCHASE TICKETS");
        for (Event e : attendee.browse())
            for (Ticket t : attendee.ticketsFor(e.getId())) say("  " + e.getTitle() + " -> " + t);
        int ticketId = askInt("Ticket ID"), qty = askInt("Quantity");
        say("  (test card: 4111 1111 1111 1111, expiry any MM/YY)");
        Purchase p = attendee.purchaseTicket(u, ticketId, qty, ask("Card number"), ask("Expiry (MM/YY)"));
        say(String.format("  Purchase confirmed! Purchase #%d, total %.2f", p.id(), p.amount()));
    }
}

/* ========================================================================== */
/*  8. DEMO DATA + THREAD DEMONSTRATION                                       */
/* ========================================================================== */

final class DemoData {
    private DemoData() { }

    static void seed(UserService users, OrganizerService org, AdminService adm, AttendeeService att, User admin) throws EMSException {
        User o = users.createAccount("Olivia Organizer", "organizer@ems.com", "org123", Role.ORGANIZER, "Delhi");
        User a1 = users.createAccount("Arjun Attendee", "attendee@ems.com", "att123", Role.ATTENDEE, "Noida");
        users.createAccount("Meera Attendee", "meera@ems.com", "att123", Role.ATTENDEE, "Delhi");

        Event tech = org.createEvent(o, "Java Tech Summit 2026", "Talks on modern Java", LocalDate.now().plusDays(20), LocalTime.of(10, 0), "Convention Centre");
        Event music = org.createEvent(o, "Open Air Music Fest", "Live bands", LocalDate.now().plusDays(35), LocalTime.of(18, 30), "City Park");
        org.createEvent(o, "Startup Pitch Night", "Pitch to investors", LocalDate.now().plusDays(10), LocalTime.of(17, 0), "Innovation Hub");
        adm.decide(admin, tech.getId(), true);
        adm.decide(admin, music.getId(), true);
        org.createTicket(o, tech.getId(), 499.0, 100);
        org.createTicket(o, music.getId(), 999.0, 5);   // tiny stock -> used by the concurrency demo
        att.register(a1, tech.getId());
        System.out.println("[demo] Seeded: organizer@ems.com/org123, attendee@ems.com/att123, meera@ems.com/att123");
    }

    /** 12 threads race for 5 tickets - exactly 5 must succeed (no overselling). */
    static void concurrentPurchaseDemo(DataStore store, AttendeeService att, User attendee, int ticketId) throws Exception {
        System.out.println("[demo] 12 threads competing for the remaining tickets of ticket #" + ticketId + " ...");
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            results.add(pool.submit(() -> {
                startGun.await();
                try { att.purchaseTicket(attendee, ticketId, 1, "4111 1111 1111 1111", "12/30"); return true; }
                catch (SoldOutException e) { return false; }
            }));
        }
        startGun.countDown();
        int ok = 0;
        for (Future<Boolean> f : results) if (f.get()) ok++;
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        Ticket t = store.findTicket(ticketId).orElseThrow();
        System.out.printf("[demo] successful=%d, rejected(sold out)=%d, sold=%d/%d  -> %s%n", ok, 12 - ok, t.getSold(), t.getQuantity(),
                t.getSold() <= t.getQuantity() ? "NO OVERSELLING" : "BUG!");
    }
}

/* ========================================================================== */
/*  9. APPLICATION ENTRY POINT                                                */
/* ========================================================================== */

public class EventManagementSystem {

    public static void main(String[] args) {
        String jdbcUrl = null, jdbcUser = null, jdbcPass = null;
        boolean web = false, demo = false;
        int port = 8080;

        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--jdbc-url" -> jdbcUrl = args[++i];
                    case "--jdbc-user" -> jdbcUser = args[++i];
                    case "--jdbc-pass" -> jdbcPass = args[++i];
                    case "--web" -> web = true;
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--demo" -> demo = true;
                    default -> { usage(); return; }
                }
            }
        } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) { usage(); return; }

        try {
            DataStore store = jdbcUrl == null ? new InMemoryStore() : new JdbcStore(jdbcUrl, jdbcUser, jdbcPass);
            System.out.println("[init] Storage: " + (jdbcUrl == null ? "in-memory collections" : "JDBC -> " + jdbcUrl));

            // default settings (only written if missing)
            Map<String, String> defaults = new LinkedHashMap<>();
            defaults.put("system_name", "Online Event Management System");
            defaults.put("auto_approve_events", "false");
            defaults.put("max_tickets_per_purchase", "10");
            defaults.put("session_timeout_minutes", "30");
            for (var d : defaults.entrySet()) if (store.getSetting(d.getKey(), null) == null) store.setSetting(d.getKey(), d.getValue());

            // wire up services (dependency injection by constructor)
            NotificationService notifier = new NotificationService(store);
            UserService users = new UserService(store);
            AdminService adminSvc = new AdminService(store, users, notifier);
            OrganizerService orgSvc = new OrganizerService(store, notifier);
            AttendeeService attSvc = new AttendeeService(store, notifier, new MockPaymentProcessor(), users);
            StatsService stats = new StatsService(store);

            // default admin
            User admin = store.findUserByEmail("admin@ems.com").orElse(null);
            if (admin == null) admin = users.createAccount("System Admin", "admin@ems.com", "admin123", Role.ADMIN, "HQ");
            System.out.println("[init] Default admin -> admin@ems.com / admin123");

            if (demo) {
                if (store.findUserByEmail("organizer@ems.com").isPresent()) {
                    System.out.println("[demo] Demo data already present - skipping seeding.");
                } else {
                    DemoData.seed(users, orgSvc, adminSvc, attSvc, admin);
                    User attendee = store.findUserByEmail("attendee@ems.com").orElseThrow();
                    int scarceTicket = -1;
                    for (Event ev : store.events())
                        for (Ticket t : store.ticketsForEvent(ev.getId()))
                            if (t.getQuantity() == 5) scarceTicket = t.getId();
                    DemoData.concurrentPurchaseDemo(store, attSvc, attendee, scarceTicket);
                    notifier.flush();
                }
            }

            if (web) {
                new WebServer(port, store, users, adminSvc, orgSvc, attSvc, stats).start();
                System.out.println("[web] Server running at http://localhost:" + port + "/   (Ctrl+C to stop)");
            } else {
                new ConsoleApp(store, users, adminSvc, orgSvc, attSvc, stats, notifier).run();
            }
        } catch (EMSException e) {
            System.err.println("Startup failed: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Unexpected failure: " + e);
        }
    }

    private static void usage() {
        System.out.println("""
                Usage: java EventManagementSystem [options]
                  --demo                 seed sample data and run the multi-thread purchase demo
                  --web                  start the HTTP server instead of the console UI
                  --port <n>             HTTP port (default 8080)
                  --jdbc-url <url>       use JDBC storage, e.g. jdbc:sqlite:ems.db or jdbc:mysql://localhost:3306/ems
                  --jdbc-user <user>     JDBC username (MySQL)
                  --jdbc-pass <pass>     JDBC password (MySQL)""");
    }
}
