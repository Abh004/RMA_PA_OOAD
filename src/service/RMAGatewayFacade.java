package src.service;

// SCM Exception Framework Imports
import com.scm.exceptions.*;
import com.scm.exceptions.categories.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.UUID;
import src.core.*;
import src.core.VocData;
import src.patterns.*;

/**
 * Integrated Facade for Product Advancement and Returns.
 * Implements SCM standard interfaces for unified exception handling.
 */
public class RMAGatewayFacade
    implements
        IInputValidationExceptionSource,
        ISystemInfrastructureExceptionSource
{

    private static final String DEFAULT_CUSTOMER_ID = "CUST-01";
    private static final String DEFAULT_RETURN_DOCK_BIN = "BIN-RETURN-DOCK";
    private static final String DEFAULT_RETURN_ZONE_BIN = "BIN-RETURNS-ZONE";

    private final ReturnValidationHandler validationChain;
    private final ReturnDAO dao = new ReturnDAO();
    private final VocAnalyzer vocAnalyzer = new VocAnalyzer();

    // Centralized SCM Exception Handler reference
    private SCMExceptionHandler scmHandler;

    public RMAGatewayFacade() {
        this.validationChain = ValidationChainFactory.createDefaultChain();
    }

    /**
     * Mandated by SCM framework to inject the central handler at startup.
     */
    @Override
    public void registerHandler(SCMExceptionHandler h) {
        this.scmHandler = h;
        // Pass the handler down to the ML component
        this.vocAnalyzer.registerHandler(h);
    }

    public void processReturn(
        ReturnRequest request,
        String condition,
        String notes
    ) {
        // --- Category 1: Input Validation Check (ID 11: INVALID_REFUND_AMOUNT) ---
        // Ensuring business rules are met before processing
        if (request.basePrice <= 0) {
            fireInvalidReference(
                11,
                "base_price",
                String.valueOf(request.basePrice)
            );
            return; // Halt immediately as per specification
        }

        try {
            InspectedItem item = new InspectedItem(request, condition, notes);
            ValidationResult val = validationChain.handle(item);

            String status = val.approved ? "APPROVED" : "REJECTED";
            String vocInfo = "None";

            if (val.approved) {
                // VocAnalyzer handles Category 10 ML errors internally
                VocData voc = vocAnalyzer.processComment(
                    request.customerComment
                );
                vocInfo = "Sentiment: " + voc.sentiment;
            } else {
                // --- Category 5 Logic: Warranty Expired (ID 207) ---
                // Mapping custom validation result to SCM registry
                if (val.message.contains("warranty")) {
                    raiseUnregistered(
                        207,
                        Severity.MINOR,
                        "Policy rejection: " + val.message
                    );
                }
            }

            String returnRequestId =
                "RET-" + UUID.randomUUID().toString().substring(0, 8);

            // 1) Core Returns subsystem write
            dao.insertProductReturn(
                returnRequestId,
                request.orderId,
                DEFAULT_CUSTOMER_ID,
                "Category: " +
                    request.category +
                    " | Product: " +
                    request.productId,
                notes,
                request.customerComment,
                "Condition: " + condition + " | " + vocInfo,
                status
            );

            // 2) Warehouse + Inventory integration (only for approved returns)
            if (val.approved) {
                int returnQty = 1;

                if (dao.productExists(request.productId)) {
                    dao.insertWarehouseReturn(
                        returnRequestId,
                        request.productId,
                        returnQty,
                        mapConditionToWarehouseStatus(condition)
                    );
                    dao.insertStockMovementForReturn(
                        "MOV-" + UUID.randomUUID().toString().substring(0, 8),
                        DEFAULT_RETURN_DOCK_BIN,
                        DEFAULT_RETURN_ZONE_BIN,
                        request.productId,
                        returnQty
                    );
                    dao.insertStockAdjustmentForReturn(
                        "ADJ-" + UUID.randomUUID().toString().substring(0, 8),
                        request.productId,
                        returnQty,
                        "SYSTEM",
                        "Approved customer return: " + returnRequestId
                    );
                } else {
                    // Resource not found (ID 166: ITEM_NOT_FOUND)
                    raiseUnregistered(
                        166,
                        Severity.MAJOR,
                        "Product ID not found in inventory: " +
                            request.productId
                    );
                }
            }
        } catch (java.sql.SQLException e) {
            // --- Category 8: Infrastructure Failure (ID 51: DB_CONNECTION_FAILED) ---
            firePlatformFailure(
                51,
                "MySQL_Database",
                "INSERT_RETURN",
                e.getMessage()
            );
        } catch (Exception e) {
            // --- ID 0: Catch-all for unlisted exceptions ---
            firePlatformFailure(
                0,
                "Subsystem_Facade",
                "GENERAL_PROCESS",
                "UNREGISTERED_EXCEPTION: " + e.getMessage()
            );
        }
    }

    // --- SCM INTERFACE IMPLEMENTATIONS ---

    @Override
    public void fireInvalidReference(
        int exceptionId,
        String refType,
        String refValue
    ) {
        if (scmHandler == null) return; // Safeguard
        scmHandler.handle(
            new SCMExceptionEvent(
                exceptionId,
                "INVALID_REFUND_AMOUNT",
                Severity.MAJOR,
                "Product Returns",
                "Refund amount is negative, zero, or exceeds original payment.",
                refType + "=" + refValue
            )
        );
    }

    @Override
    public void firePlatformFailure(
        int exceptionId,
        String component,
        String operation,
        String detail
    ) {
        if (scmHandler == null) return;
        String name = (exceptionId == 0)
            ? "UNREGISTERED_EXCEPTION"
            : "DB_CONNECTION_FAILED";
        scmHandler.handle(
            new SCMExceptionEvent(
                exceptionId,
                name,
                Severity.MAJOR,
                "Product Returns",
                "Subsystem infrastructure error.",
                component + " during " + operation + ": " + detail
            )
        );
    }

    /**
     * Internal helper to handle registry IDs not explicitly covered by method signatures.
     */
    private void raiseUnregistered(int id, Severity sev, String detail) {
        if (scmHandler == null) return;
        scmHandler.handle(
            new SCMExceptionEvent(
                id,
                "BUSINESS_LOGIC_VIOLATION",
                sev,
                "Product Returns",
                "Validation or logic failure.",
                detail
            )
        );
    }

    // Unused mandated methods
    @Override
    public void fireInvalidInput(int id, String f, String v, String r) {}

    @Override
    public void fireConfigurationError(int id, String k, String r) {}

    @Override
    public void fireInvalidStateTransition(
        int id,
        String e,
        String fs,
        String ts
    ) {}

    @Override
    public void fireValidationFailure(int id, String t, String e, String r) {}

    @Override
    public void fireProcessingError(int id, String p, String e, String r) {}

    @Override
    public void firePerformanceDegradation(int id, String c, long t, long a) {}

    @Override
    public void fireRenderOrFormatError(int id, String c, String f, String r) {}

    private String mapConditionToWarehouseStatus(String condition) {
        if (condition == null) return "PARTIAL";
        String normalized = condition.trim().toUpperCase();
        if (
            normalized.contains("SEALED") || normalized.contains("GOOD")
        ) return "GOOD";
        if (normalized.contains("DAMAGED")) return "DAMAGED";
        if (normalized.contains("REJECT")) return "REJECTED";
        return "PARTIAL";
    }
}
