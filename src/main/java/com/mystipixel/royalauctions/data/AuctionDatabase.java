package com.mystipixel.royalauctions.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * All persistence for RoyalAuctions. One JDBC/HikariCP implementation serves both
 * backends; the only differences are the connection URL, driver and the "big text"
 * column type. Every method here is blocking and MUST be called off the main thread
 * (see {@code AuctionService}, which schedules them).
 */
public final class AuctionDatabase {

    public enum Type {
        SQLITE, MYSQL
    }

    private final File dataFolder;
    private final ConfigurationSection config;
    private final Logger logger;

    private Type type;
    private HikariDataSource dataSource;

    public AuctionDatabase(File dataFolder, ConfigurationSection storageConfig, Logger logger) {
        this.dataFolder = dataFolder;
        this.config = storageConfig;
        this.logger = logger;
    }

    // ------------------------------------------------------------------ lifecycle

    public void init() throws SQLException {
        String rawType = config.getString("type", "SQLITE").toUpperCase();
        this.type = "MYSQL".equals(rawType) ? Type.MYSQL : Type.SQLITE;

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("RoyalAuctions");

        if (type == Type.MYSQL) {
            ConfigurationSection my = config.getConfigurationSection("mysql");
            String host = my.getString("host", "localhost");
            int port = my.getInt("port", 3306);
            String database = my.getString("database", "royalauctions");
            String props = my.getString("properties", "useSSL=false");
            registerDriver("com.mysql.cj.jdbc.Driver");
            hikari.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?" + props);
            hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
            hikari.setUsername(my.getString("username", "root"));
            hikari.setPassword(my.getString("password", ""));
            hikari.setMaximumPoolSize(Math.max(1, my.getInt("pool-size", 10)));
        } else {
            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                logger.warning("Could not create plugin data folder: " + dataFolder);
            }
            File db = new File(dataFolder, config.getString("sqlite-file", "auctions.db"));
            registerDriver("org.sqlite.JDBC");
            hikari.setJdbcUrl("jdbc:sqlite:" + db.getAbsolutePath());
            hikari.setDriverClassName("org.sqlite.JDBC");
            // SQLite is a single-writer engine; one pooled connection + WAL avoids SQLITE_BUSY.
            hikari.setMaximumPoolSize(1);
            hikari.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;");
        }

        this.dataSource = new HikariDataSource(hikari);
        createSchema();
        logger.info("Connected to " + type + " storage.");
    }

    private void registerDriver(String driverClass) {
        // Paper's library loader puts the JDBC driver on this plugin's classloader; forcing the
        // class to load here registers it with DriverManager before Hikari asks for a connection.
        try {
            Class.forName(driverClass, true, getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            logger.log(Level.WARNING, "JDBC driver not found on classpath: " + driverClass, e);
        }
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    private String bigText() {
        return type == Type.MYSQL ? "LONGTEXT" : "TEXT";
    }

    private void createSchema() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS ra_listings ("
                    + "id CHAR(36) PRIMARY KEY,"
                    + "seller_id CHAR(36) NOT NULL,"
                    + "seller_name VARCHAR(32) NOT NULL,"
                    + "item_data " + bigText() + " NOT NULL,"
                    + "display_name VARCHAR(256) NOT NULL,"
                    + "category VARCHAR(64) NOT NULL,"
                    + "type VARCHAR(16) NOT NULL DEFAULT 'BIN',"
                    + "price DOUBLE PRECISION NOT NULL,"
                    + "current_bid DOUBLE PRECISION,"
                    + "top_bidder_id CHAR(36),"
                    + "top_bidder_name VARCHAR(32),"
                    + "bid_count INT NOT NULL DEFAULT 0,"
                    + "created_at BIGINT NOT NULL,"
                    + "expires_at BIGINT NOT NULL,"
                    + "status VARCHAR(16) NOT NULL,"
                    + "buyer_id CHAR(36),"
                    + "sold_at BIGINT)");
            // Upgrade databases created before bidding / tiers were added. Existing rows get a NULL
            // tier, which simply means "no rarity" — they still show under the unfiltered view.
            addColumn(st, "tier", "VARCHAR(32)");
            addColumn(st, "type", "VARCHAR(16) NOT NULL DEFAULT 'BIN'");
            addColumn(st, "current_bid", "DOUBLE PRECISION");
            addColumn(st, "top_bidder_id", "CHAR(36)");
            addColumn(st, "top_bidder_name", "VARCHAR(32)");
            addColumn(st, "bid_count", "INT NOT NULL DEFAULT 0");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS ra_collection ("
                    + "id CHAR(36) PRIMARY KEY,"
                    + "owner_id CHAR(36) NOT NULL,"
                    + "item_data " + bigText() + " NOT NULL,"
                    + "reason VARCHAR(16) NOT NULL,"
                    + "created_at BIGINT NOT NULL)");
            // Every bid ever placed. ra_listings only remembers the *current* top bidder, so this is
            // what makes "auctions I've bid on" (including ones I've since been outbid on) knowable.
            st.executeUpdate("CREATE TABLE IF NOT EXISTS ra_bids ("
                    + "id " + autoIncrementPk() + ","
                    + "listing_id CHAR(36) NOT NULL,"
                    + "bidder_id CHAR(36) NOT NULL,"
                    + "bidder_name VARCHAR(32) NOT NULL,"
                    + "amount DOUBLE PRECISION NOT NULL,"
                    + "created_at BIGINT NOT NULL)");
            createIndex(st, "idx_ra_listings_status", "ra_listings(status)");
            createIndex(st, "idx_ra_listings_seller", "ra_listings(seller_id)");
            createIndex(st, "idx_ra_collection_owner", "ra_collection(owner_id)");
            createIndex(st, "idx_ra_bids_bidder", "ra_bids(bidder_id)");
            createIndex(st, "idx_ra_bids_listing", "ra_bids(listing_id)");
        }
    }

    private String autoIncrementPk() {
        return type == Type.MYSQL ? "BIGINT PRIMARY KEY AUTO_INCREMENT" : "INTEGER PRIMARY KEY AUTOINCREMENT";
    }

    private void addColumn(Statement st, String column, String definition) {
        // ALTER TABLE ... ADD COLUMN throws if the column already exists; that's the "already migrated" case.
        try {
            st.executeUpdate("ALTER TABLE ra_listings ADD COLUMN " + column + " " + definition);
        } catch (SQLException ignored) {
            // Column already present.
        }
    }

    private void createIndex(Statement st, String name, String target) {
        // MySQL lacks CREATE INDEX IF NOT EXISTS on older versions; a duplicate is harmless.
        try {
            st.executeUpdate("CREATE INDEX IF NOT EXISTS " + name + " ON " + target);
        } catch (SQLException ignored) {
            try {
                st.executeUpdate("CREATE INDEX " + name + " ON " + target);
            } catch (SQLException ignoredToo) {
                // Index already exists.
            }
        }
    }

    // ------------------------------------------------------------------ listings

    public void insertListing(Listing l) throws SQLException {
        String sql = "INSERT INTO ra_listings "
                + "(id,seller_id,seller_name,item_data,display_name,category,tier,type,price,"
                + "current_bid,top_bidder_id,top_bidder_name,bid_count,created_at,expires_at,status) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, l.id().toString());
            ps.setString(2, l.sellerId().toString());
            ps.setString(3, l.sellerName());
            ps.setString(4, ItemSerialization.toBase64(l.itemData()));
            ps.setString(5, l.displayName());
            ps.setString(6, l.category());
            ps.setString(7, l.tier());
            ps.setString(8, l.type().name());
            ps.setDouble(9, l.price());
            ps.setDouble(10, l.currentBid());
            ps.setString(11, l.topBidderId() == null ? null : l.topBidderId().toString());
            ps.setString(12, l.topBidderName());
            ps.setInt(13, l.bidCount());
            ps.setLong(14, l.createdAt());
            ps.setLong(15, l.expiresAt());
            ps.setString(16, l.status().name());
            ps.executeUpdate();
        }
    }

    public List<Listing> activeListings() throws SQLException {
        List<Listing> out = new ArrayList<>();
        String sql = "SELECT * FROM ra_listings WHERE status='ACTIVE' ORDER BY created_at DESC";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(mapListing(rs));
            }
        }
        return out;
    }

    public List<Listing> activeListingsBySeller(UUID sellerId) throws SQLException {
        List<Listing> out = new ArrayList<>();
        String sql = "SELECT * FROM ra_listings WHERE status='ACTIVE' AND seller_id=? ORDER BY created_at DESC";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sellerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapListing(rs));
                }
            }
        }
        return out;
    }

    /**
     * Every still-active auction this player has bid on — whether they're currently winning or have
     * been outbid. Backed by {@code ra_bids}, so it only knows bids placed since that table existed.
     */
    public List<Listing> activeListingsBidOnBy(UUID bidderId) throws SQLException {
        List<Listing> out = new ArrayList<>();
        String sql = "SELECT l.* FROM ra_listings l WHERE l.status='ACTIVE' AND EXISTS ("
                + "SELECT 1 FROM ra_bids b WHERE b.listing_id = l.id AND b.bidder_id = ?) "
                + "ORDER BY l.expires_at ASC";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, bidderId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapListing(rs));
                }
            }
        }
        return out;
    }

    /** Repair a listing whose stored category no longer exists (categories were renamed in config). */
    public void updateCategory(UUID id, String category) throws SQLException {
        String sql = "UPDATE ra_listings SET category=? WHERE id=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, category);
            ps.setString(2, id.toString());
            ps.executeUpdate();
        }
    }

    public int countActiveBySeller(UUID sellerId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM ra_listings WHERE status='ACTIVE' AND seller_id=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sellerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public int countActive() throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM ra_listings WHERE status='ACTIVE'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public Optional<Listing> getListing(UUID id) throws SQLException {
        String sql = "SELECT * FROM ra_listings WHERE id=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapListing(rs)) : Optional.empty();
            }
        }
    }

    /** Atomically flip ACTIVE→SOLD. Returns true only if this call is the one that sold it. */
    public boolean markSoldIfActive(UUID id, UUID buyerId, long soldAt) throws SQLException {
        String sql = "UPDATE ra_listings SET status='SOLD', buyer_id=?, sold_at=? WHERE id=? AND status='ACTIVE'";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, buyerId.toString());
            ps.setLong(2, soldAt);
            ps.setString(3, id.toString());
            return ps.executeUpdate() == 1;
        }
    }

    /** Undo a SOLD flip if the buyer's payment failed after we reserved the listing. */
    public boolean revertSold(UUID id, UUID buyerId) throws SQLException {
        String sql = "UPDATE ra_listings SET status='ACTIVE', buyer_id=NULL, sold_at=NULL "
                + "WHERE id=? AND status='SOLD' AND buyer_id=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id.toString());
            ps.setString(2, buyerId.toString());
            return ps.executeUpdate() == 1;
        }
    }

    /** Result of a bid attempt, including who to refund if this bid outbid someone. */
    public static final class BidOutcome {
        public final boolean success;
        public final UUID previousBidderId; // null if this was the first bid or on failure
        public final String previousBidderName;
        public final double previousBid;
        public final String failureReason;  // "GONE" or "OUTBID" when success is false

        private BidOutcome(boolean success, UUID previousBidderId, String previousBidderName,
                           double previousBid, String failureReason) {
            this.success = success;
            this.previousBidderId = previousBidderId;
            this.previousBidderName = previousBidderName;
            this.previousBid = previousBid;
            this.failureReason = failureReason;
        }

        static BidOutcome ok(UUID prevBidder, String prevName, double prevBid) {
            return new BidOutcome(true, prevBidder, prevName, prevBid, null);
        }

        static BidOutcome fail(String reason) {
            return new BidOutcome(false, null, null, 0, reason);
        }
    }

    /**
     * Place a bid inside a transaction, using the row's {@code bid_count} as an optimistic lock so
     * two concurrent bids can't both win. Returns who previously led (to refund them) on success.
     */
    public BidOutcome placeBid(UUID id, UUID bidderId, String bidderName, double amount) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                UUID prevBidder = null;
                String prevBidderName = null;
                double prevBid = 0;
                int seenCount;
                try (PreparedStatement sel = c.prepareStatement(
                        "SELECT status, current_bid, top_bidder_id, top_bidder_name, bid_count "
                        + "FROM ra_listings WHERE id=?")) {
                    sel.setString(1, id.toString());
                    try (ResultSet rs = sel.executeQuery()) {
                        if (!rs.next()) {
                            c.rollback();
                            return BidOutcome.fail("GONE");
                        }
                        if (!"ACTIVE".equals(rs.getString("status"))) {
                            c.rollback();
                            return BidOutcome.fail("GONE");
                        }
                        double curBid = rs.getDouble("current_bid");
                        seenCount = rs.getInt("bid_count");
                        String prevBidderStr = rs.getString("top_bidder_id");
                        if (seenCount > 0 && amount <= curBid) {
                            c.rollback();
                            return BidOutcome.fail("OUTBID");
                        }
                        if (seenCount > 0 && prevBidderStr != null) {
                            prevBidder = UUID.fromString(prevBidderStr);
                            prevBidderName = rs.getString("top_bidder_name");
                            prevBid = curBid;
                        }
                    }
                }
                try (PreparedStatement upd = c.prepareStatement(
                        "UPDATE ra_listings SET current_bid=?, top_bidder_id=?, top_bidder_name=?, "
                        + "bid_count=bid_count+1 WHERE id=? AND status='ACTIVE' AND bid_count=?")) {
                    upd.setDouble(1, amount);
                    upd.setString(2, bidderId.toString());
                    upd.setString(3, bidderName);
                    upd.setString(4, id.toString());
                    upd.setInt(5, seenCount);
                    if (upd.executeUpdate() != 1) {
                        c.rollback();
                        return BidOutcome.fail("OUTBID"); // lost the optimistic race
                    }
                }
                // Record the bid itself. The listing row only ever remembers the *current* leader, so
                // without this there'd be no way to know which auctions a player has bid on once outbid.
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO ra_bids (listing_id, bidder_id, bidder_name, amount, created_at) "
                        + "VALUES (?,?,?,?,?)")) {
                    ins.setString(1, id.toString());
                    ins.setString(2, bidderId.toString());
                    ins.setString(3, bidderName);
                    ins.setDouble(4, amount);
                    ins.setLong(5, System.currentTimeMillis());
                    ins.executeUpdate();
                }
                c.commit();
                return BidOutcome.ok(prevBidder, prevBidderName, prevBid);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    /** Roll a bid back to the previous leader if charging the new bidder failed. */
    public boolean revertBid(UUID id, UUID newBidderId, UUID prevBidderId, String prevBidderName, double prevBid)
            throws SQLException {
        String sql = "UPDATE ra_listings SET current_bid=?, top_bidder_id=?, top_bidder_name=?, "
                + "bid_count=bid_count-1 WHERE id=? AND status='ACTIVE' AND top_bidder_id=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDouble(1, prevBid);
            ps.setString(2, prevBidderId == null ? null : prevBidderId.toString());
            ps.setString(3, prevBidderName);
            ps.setString(4, id.toString());
            ps.setString(5, newBidderId.toString());
            return ps.executeUpdate() == 1;
        }
    }

    /** Atomically flip ACTIVE→CANCELLED for the owning seller, but never once a bid has landed. */
    public boolean cancelIfActive(UUID id, UUID sellerId) throws SQLException {
        String sql = "UPDATE ra_listings SET status='CANCELLED' "
                + "WHERE id=? AND seller_id=? AND status='ACTIVE' AND bid_count=0";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id.toString());
            ps.setString(2, sellerId.toString());
            return ps.executeUpdate() == 1;
        }
    }

    /** Atomically flip ACTIVE→EXPIRED. Returns true only for the caller that expired it. */
    public boolean markExpiredIfActive(UUID id) throws SQLException {
        String sql = "UPDATE ra_listings SET status='EXPIRED' WHERE id=? AND status='ACTIVE'";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id.toString());
            return ps.executeUpdate() == 1;
        }
    }

    public List<Listing> dueExpirations(long now) throws SQLException {
        List<Listing> out = new ArrayList<>();
        String sql = "SELECT * FROM ra_listings WHERE status='ACTIVE' AND expires_at<=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapListing(rs));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ collection

    public void addCollectionItem(CollectionItem item) throws SQLException {
        String sql = "INSERT INTO ra_collection (id,owner_id,item_data,reason,created_at) VALUES (?,?,?,?,?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, item.id().toString());
            ps.setString(2, item.ownerId().toString());
            ps.setString(3, ItemSerialization.toBase64(item.itemData()));
            ps.setString(4, item.reason().name());
            ps.setLong(5, item.createdAt());
            ps.executeUpdate();
        }
    }

    public List<CollectionItem> collectionItems(UUID ownerId) throws SQLException {
        List<CollectionItem> out = new ArrayList<>();
        String sql = "SELECT * FROM ra_collection WHERE owner_id=? ORDER BY created_at ASC";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, ownerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapCollection(rs));
                }
            }
        }
        return out;
    }

    /** Atomically remove one collection row for its owner. True only if this call removed it. */
    public boolean removeCollectionItem(UUID id, UUID ownerId) throws SQLException {
        String sql = "DELETE FROM ra_collection WHERE id=? AND owner_id=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id.toString());
            ps.setString(2, ownerId.toString());
            return ps.executeUpdate() == 1;
        }
    }

    // ------------------------------------------------------------------ mapping

    private Listing mapListing(ResultSet rs) throws SQLException {
        String topBidder = rs.getString("top_bidder_id");
        String typeStr = rs.getString("type");
        return new Listing(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("seller_id")),
                rs.getString("seller_name"),
                ItemSerialization.fromBase64(rs.getString("item_data")),
                rs.getString("display_name"),
                rs.getString("category"),
                rs.getString("tier"),
                ListingType.fromString(typeStr, ListingType.BIN),
                rs.getDouble("price"),
                rs.getLong("created_at"),
                rs.getLong("expires_at"),
                ListingStatus.valueOf(rs.getString("status")),
                rs.getDouble("current_bid"),
                topBidder == null ? null : UUID.fromString(topBidder),
                rs.getString("top_bidder_name"),
                rs.getInt("bid_count"));
    }

    private CollectionItem mapCollection(ResultSet rs) throws SQLException {
        return new CollectionItem(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("owner_id")),
                ItemSerialization.fromBase64(rs.getString("item_data")),
                CollectionItem.Reason.valueOf(rs.getString("reason")),
                rs.getLong("created_at"));
    }
}
