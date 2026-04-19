package inventory_subsystem;

import com.scm.handler.SCMExceptionHandler;
import com.scm.subsystems.InventorySubsystem;

/**
 * Routes inventory exception events to the SCM Exception Handler JAR.
 *
 * Uses InventorySubsystem.INSTANCE from scm-exception-handler-v3.jar for all
 * registered exception IDs (110, 166, 167). IDs 200 and 201 (REORDER_TRIGGER /
 * SAFETY_STOCK_BREACH) are not exposed as public methods on InventorySubsystem,
 * so they are logged to stderr as a fallback.
 *
 * registerHandler() accepts the SCMExceptionHandler singleton for API compatibility
 * with the interface spec, but the subsystem singleton manages its own handler
 * internally — no additional wiring is required.
 */
public class InventoryExceptionSource {

    // Kept for spec compliance; InventorySubsystem manages its own handler internally.
    @SuppressWarnings("unused")
    private SCMExceptionHandler handler;

    private final InventorySubsystem subsystem = InventorySubsystem.INSTANCE;

    /**
     * Register the SCM exception handler singleton.
     * Per the interface spec: exceptionSource.registerHandler(handler);
     */
    public void registerHandler(SCMExceptionHandler handler) {
        this.handler = handler;
    }

    /**
     * Fire a "resource not found" event.
     * Supported IDs:
     *   166 → ITEM_NOT_FOUND → onItemNotFound(resourceId)
     */
    public void fireResourceNotFound(int id, String type, String resourceId) {
        if (id == 166) {
            subsystem.onItemNotFound(resourceId);
        } else {
            System.err.println("[Inventory] UNREGISTERED: resource not found id=" + id
                    + " type=" + type + " id=" + resourceId);
        }
    }

    /**
     * Fire a "resource exhausted" event.
     * Supported IDs:
     *   167 → INSUFFICIENT_STOCK → onInsufficientStock(resourceId, requested, available)
     *   200 → REORDER_TRIGGER   → stderr (not a public method on InventorySubsystem)
     *   201 → SAFETY_STOCK_BREACH → stderr (not a public method on InventorySubsystem)
     */
    public void fireResourceExhausted(int id, String type,
                                      String resourceId,
                                      int requested, int available) {
        if (id == 167) {
            subsystem.onInsufficientStock(resourceId, requested, available);
        } else {
            // IDs 200, 201 are in the Inventory exception contract but not exposed
            // as public methods on InventorySubsystem in the current JAR version.
            System.err.println("[Inventory] UNREGISTERED: resource exhausted id=" + id
                    + " type=" + type + " resource=" + resourceId
                    + " requested=" + requested + " available=" + available);
        }
    }

    /**
     * Fire a "conflict" event.
     * Supported IDs:
     *   110 → STOCK_UPDATE_CONFLICT → onStockUpdateConflict(entityId)
     */
    public void fireConflict(int id, String entityType,
                             String entityId, String reason) {
        if (id == 110) {
            subsystem.onStockUpdateConflict(entityId);
        } else {
            System.err.println("[Inventory] UNREGISTERED: conflict id=" + id
                    + " type=" + entityType + " entity=" + entityId
                    + " reason=" + reason);
        }
    }
}
