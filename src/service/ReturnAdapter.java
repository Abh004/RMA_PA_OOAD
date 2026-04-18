package src.service;

import com.jackfruit.scm.database.adapter.ExceptionHandlingAdapter;
import com.jackfruit.scm.database.adapter.InventoryAdapter;
import com.jackfruit.scm.database.adapter.WarehouseManagementAdapter;
import com.jackfruit.scm.database.facade.SupplyChainDatabaseFacade;
import com.jackfruit.scm.database.model.InventoryModels;
import com.jackfruit.scm.database.model.SubsystemException;
import com.jackfruit.scm.database.model.WarehouseModels;
import java.io.FileInputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.Properties;

/**
 * ReturnAdapter replaces the old ReturnDAO + DBConnectionPool.
 *
 * Uses the database team's JAR adapters for warehouse/inventory writes.
 * productExists() uses a direct JDBC query because the JAR's listProducts()
 * throws internally due to a RowMapper column mismatch.
 *
 * Reads database.properties from disk (not classpath) so the JAR's
 * own bundled copy cannot override our credentials.
 */
public class ReturnAdapter {

    private final WarehouseManagementAdapter warehouseAdapter;
    private final InventoryAdapter inventoryAdapter;
    private final ExceptionHandlingAdapter exceptionAdapter;

    private final String dbUrl;
    private final String dbUser;
    private final String dbPass;

    public ReturnAdapter(SupplyChainDatabaseFacade facade) {
        this.warehouseAdapter = new WarehouseManagementAdapter(facade);
        this.inventoryAdapter = new InventoryAdapter(facade);
        this.exceptionAdapter = new ExceptionHandlingAdapter(facade);

        // Read from disk explicitly so the JAR's bundled database.properties
        // on the classpath cannot take priority over ours.
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream("database.properties")) {
            props.load(fis);
        } catch (Exception e) {
            System.err.println("[ReturnAdapter] Could not load database.properties: "
                    + e.getMessage());
        }

        this.dbUrl  = props.getProperty("db.url",      "jdbc:mysql://localhost:3306/OOAD");
        this.dbUser = props.getProperty("db.username",  "root");
        this.dbPass = props.getProperty("db.password",  "");
    }

    // -------------------------------------------------------------------------
    // WAREHOUSE
    // -------------------------------------------------------------------------

    public void insertWarehouseReturn(
            String returnId,
            String productId,
            int returnQty,
            String conditionStatus) {

        WarehouseModels.WarehouseReturn wr = new WarehouseModels.WarehouseReturn(
                returnId, productId, returnQty, conditionStatus, LocalDateTime.now());
        warehouseAdapter.createWarehouseReturn(wr);
    }

    public void insertStockMovementForReturn(
            String movementId,
            String fromBin,
            String toBin,
            String productId,
            int movedQty) {

        WarehouseModels.StockMovement sm = new WarehouseModels.StockMovement(
                movementId, "RETURN", fromBin, toBin, productId, movedQty, LocalDateTime.now());
        warehouseAdapter.createStockMovement(sm);
    }

    // -------------------------------------------------------------------------
    // INVENTORY
    // -------------------------------------------------------------------------

    /**
     * Direct JDBC product lookup — bypasses the JAR's broken listProducts().
     */
    public boolean productExists(String productId) {
        String sql = "SELECT 1 FROM products WHERE product_id = ? LIMIT 1";
        try (
            Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass);
            PreparedStatement ps = conn.prepareStatement(sql)
        ) {
            ps.setString(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            System.err.println("[ReturnAdapter] productExists failed: " + e.getMessage());
            return false;
        }
    }

    public void insertStockAdjustmentForReturn(
            String adjustmentId,
            String productId,
            int qty,
            String adjustedBy,
            String reason) {

        InventoryModels.StockAdjustment sa = new InventoryModels.StockAdjustment(
                adjustmentId, productId, null, "INCREASE", qty, reason,
                adjustedBy, LocalDateTime.now(), null, adjustedBy, reason,
                false, LocalDateTime.now());
        inventoryAdapter.createStockAdjustment(sa);
    }

    // -------------------------------------------------------------------------
    // EXCEPTION LOGGING
    // -------------------------------------------------------------------------

    public void logException(
            String subsystemName,
            String severity,
            String message,
            String stackTrace) {

        SubsystemException ex = new SubsystemException();
        ex.setExceptionId(java.util.UUID.randomUUID().toString());
        ex.setSubsystemName(subsystemName);
        ex.setSeverity(severity);
        ex.setExceptionMessage(message);
        ex.setStackTrace(stackTrace);
        ex.setStatus("OPEN");
        ex.setTimestampUtc(LocalDateTime.now());
        ex.setCreatedAt(LocalDateTime.now());
        exceptionAdapter.logException(ex);
    }
}
