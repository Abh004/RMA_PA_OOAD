package src.service;

import java.sql.*;

public class ReturnDAO {

    private final DBConnectionPool pool = DBConnectionPool.getInstance();

    public void insertProductReturn(
        String rId,
        String oId,
        String cId,
        String pDet,
        String dDet,
        String feed,
        String tDet,
        String status
    ) throws SQLException {
        // Aligned with 'product_returns' table in OOAD schema
        String sql =
            "INSERT INTO product_returns (return_request_id, order_id, customer_id, product_details, defect_details, customer_feedback, transport_details, return_status, created_at) VALUES (?,?,?,?,?,?,?,?,NOW())";
        try (
            Connection conn = pool.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql)
        ) {
            ps.setString(1, rId);
            ps.setString(2, oId);
            ps.setString(3, cId);
            ps.setString(4, pDet);
            ps.setString(5, dDet);
            ps.setString(6, feed);
            ps.setString(7, tDet);
            ps.setString(8, status);
            ps.executeUpdate();
        }
    }

    /**
     * Warehouse subsystem integration:
     * Records inbound approved returns at warehouse.
     */
    public void insertWarehouseReturn(
        String returnId,
        String productId,
        int returnQty,
        String conditionStatus
    ) throws SQLException {
        String sql =
            "INSERT INTO warehouse_returns (return_id, product_id, return_qty, condition_status, return_ts) VALUES (?,?,?,?,NOW())";
        try (
            Connection conn = pool.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql)
        ) {
            ps.setString(1, returnId);
            ps.setString(2, productId);
            ps.setInt(3, returnQty);
            ps.setString(4, conditionStatus);
            ps.executeUpdate();
        }
    }

    /**
     * Warehouse subsystem integration:
     * Creates stock movement audit entry for return flow.
     * Default-safe bin handling: from_bin/to_bin can be null.
     */
    public void insertStockMovementForReturn(
        String movementId,
        String fromBin,
        String toBin,
        String productId,
        int movedQty
    ) throws SQLException {
        String sql =
            "INSERT INTO stock_movements (movement_id, movement_type, from_bin, to_bin, product_id, moved_qty, movement_ts) VALUES (?,'RETURN',?,?,?,?,NOW())";
        try (
            Connection conn = pool.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql)
        ) {
            ps.setString(1, movementId);

            if (fromBin == null || fromBin.isBlank()) ps.setNull(
                2,
                Types.VARCHAR
            );
            else ps.setString(2, fromBin);

            if (toBin == null || toBin.isBlank()) ps.setNull(3, Types.VARCHAR);
            else ps.setString(3, toBin);

            ps.setString(4, productId);
            ps.setInt(5, movedQty);
            ps.executeUpdate();
        }
    }

    /**
     * Inventory subsystem integration:
     * Validates that product exists in inventory product master.
     */
    public boolean productExists(String productId) throws SQLException {
        String sql = "SELECT 1 FROM products WHERE product_id = ? LIMIT 1";
        try (
            Connection conn = pool.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql)
        ) {
            ps.setString(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Inventory subsystem integration:
     * Writes an audit trail entry for stock correction due to returns.
     */
    public void insertStockAdjustmentForReturn(
        String adjustmentId,
        String productId,
        int qty,
        String adjustedBy,
        String reason
    ) throws SQLException {
        String sql =
            "INSERT INTO stock_adjustments (adjustment_id, product_id, adjustment_type, quantity_adjusted, reason, adjusted_by, adjusted_at) VALUES (?,?,'INCREASE',?,?,?,NOW())";
        try (
            Connection conn = pool.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql)
        ) {
            ps.setString(1, adjustmentId);
            ps.setString(2, productId);
            ps.setInt(3, qty);
            ps.setString(4, reason);
            ps.setString(5, adjustedBy);
            ps.executeUpdate();
        }
    }

    public void logException(String sub, String sev, String msg, String trace) {
        // Aligned with 'subsystem_exceptions' table
        String sql =
            "INSERT INTO subsystem_exceptions (exception_id, subsystem_name, severity, timestamp_utc, exception_message, stack_trace, status) VALUES (UUID(),?,?,NOW(),?,?,'OPEN')";
        try (
            Connection conn = pool.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql)
        ) {
            ps.setString(1, sub);
            ps.setString(2, sev);
            ps.setString(3, msg);
            ps.setString(4, trace);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }
}
