package src.service;

import com.jackfruit.scm.database.adapter.ExceptionHandlingAdapter;
import com.jackfruit.scm.database.adapter.InventoryAdapter;
import com.jackfruit.scm.database.adapter.ReturnsAdapter;
// SCM Database Module Imports
import com.jackfruit.scm.database.facade.SupplyChainDatabaseFacade;
import com.jackfruit.scm.database.model.InventoryModels.StockAdjustmentRequest;
import com.jackfruit.scm.database.model.ReturnsModels.ProductReturnRequest;
// SCM Exception Framework Imports
import com.scm.exceptions.*;
import com.scm.exceptions.categories.*;
import java.util.ArrayList;
import java.util.UUID;
import src.core.*;
import src.core.VocData;
import src.patterns.*;

/**
 * Integrated Facade for Product Advancement and Returns.
 * Connects the Returns Subsystem with the Inventory Subsystem via shared Adapters.
 */
public class RMAGatewayFacade
    implements
        IInputValidationExceptionSource,
        ISystemInfrastructureExceptionSource
{

    private static final String DEFAULT_CUSTOMER_ID = "CUST-01";
    private static final String DEFAULT_LOCATION = "WH-MAIN-01"; // Required by Inventory API

    private final ReturnValidationHandler validationChain;
    private final VocAnalyzer vocAnalyzer = new VocAnalyzer();

    // Database Module Adapters
    private SupplyChainDatabaseFacade dbFacade;
    private ReturnsAdapter returnsAdapter;
    private InventoryAdapter inventoryAdapter;
    private ExceptionHandlingAdapter exceptionAdapter;

    private SCMExceptionHandler scmHandler;

    public RMAGatewayFacade() {
        this.validationChain = ValidationChainFactory.createDefaultChain();
        try {
            // Entry point for the shared database module
            this.dbFacade = new SupplyChainDatabaseFacade();
            this.returnsAdapter = new ReturnsAdapter(dbFacade);
            this.inventoryAdapter = new InventoryAdapter(dbFacade);
            this.exceptionAdapter = new ExceptionHandlingAdapter(dbFacade);
        } catch (Exception e) {
            System.err.println(
                "Database Module Initialization Failed: " + e.getMessage()
            );
        }
    }

    @Override
    public void registerHandler(SCMExceptionHandler h) {
        this.scmHandler = h;
        this.vocAnalyzer.registerHandler(h);
    }

    /**
     * Orchestrates the return process, including inventory validation and restock.
     */
    public void processReturn(
        ReturnRequest request,
        String condition,
        String notes
    ) {
        // --- Input Validation ---
        if (request.basePrice <= 0) {
            fireInvalidReference(
                11,
                "base_price",
                String.valueOf(request.basePrice)
            );
            return;
        }

        try {
            // 1. Inventory Validation
            // Verify item exists in Inventory Master before processing return
            if (!inventoryAdapter.productExists(request.productId)) {
                // Fire Code 166: ITEM_NOT_FOUND as mandated by Inventory contract
                raiseUnregistered(
                    166,
                    Severity.MAJOR,
                    "Product ID not found: " + request.productId
                );
                return;
            }

            InspectedItem item = new InspectedItem(
                request,
                condition,
                notes
            );
            ValidationResult val = validationChain.handle(item);
            String status = val.approved ? "APPROVED" : "REJECTED";

            VocData voc = val.approved
                ? vocAnalyzer.processComment(request.customerComment)
                : new VocData("Neutral", new ArrayList<>());

            String returnRequestId =
                "RET-" + UUID.randomUUID().toString().substring(0, 8);

            // 2. Log Return Request
            returnsAdapter.createReturnRequest(
                new ProductReturnRequest(
                    returnRequestId,
                    request.orderId,
                    DEFAULT_CUSTOMER_ID,
                    request.productId,
                    notes,
                    request.customerComment,
                    status
                )
            );

            // 3. Inventory Integration (Restock)
            if (val.approved) {
                // Execute restock via Inventory execution engine
                inventoryAdapter.createStockAdjustment(
                    new StockAdjustmentRequest(
                        "ADJ-" +
                            UUID.randomUUID().toString().substring(0, 8),
                        request.productId,
                        "INCREASE", // Type for incoming returns
                        1, // Quantity
                        "Approved return restock: " + returnRequestId,
                        "SYSTEM"
                    )
                );
            }
        } catch (Exception e) {
            // 4. Centralized Exception Logging
            if (exceptionAdapter != null) {
                exceptionAdapter.logException(
                    "Product Returns",
                    "MAJOR",
                    e.getMessage(),
                    e.toString()
                );
            }
            firePlatformFailure(
                0,
                "Subsystem_Facade",
                "GENERAL_PROCESS",
                e.getMessage()
            );
        }
    }

    // --- SCM Interface Implementations ---

    @Override
    public void fireInvalidReference(
        int exceptionId,
        String refType,
        String refValue
    ) {
        if (scmHandler == null) return;
        scmHandler.handle(
            new SCMExceptionEvent(
                exceptionId,
                "INVALID_REFUND_AMOUNT",
                Severity.MAJOR,
                "Product Returns",
                "Refund error.",
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
                "Infrastructure error.",
                component + " during " + operation + ": " + detail
            )
        );
    }

    private void raiseUnregistered(int id, Severity sev, String detail) {
        if (scmHandler == null) return;
        scmHandler.handle(
            new SCMExceptionEvent(
                id,
                "BUSINESS_LOGIC_VIOLATION",
                sev,
                "Product Returns",
                "Validation failed.",
                detail
            )
        );
    }

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
    public void fireValidationFailure(
        int id,
        String t,
        String e,
        String r
    ) {}

    @Override
    public void fireProcessingError(
        int id,
        String p,
        String e,
        String r
    ) {}

    @Override
    public void firePerformanceDegradation(
        int id,
        String c,
        long t,
        long a
    ) {}

    @Override
    public void fireRenderOrFormatError(
        int id,
        String c,
        String f,
        String r
    ) {}
}
