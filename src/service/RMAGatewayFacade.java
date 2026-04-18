package src.service;

import com.jackfruit.scm.database.adapter.InventoryAdapter;
import com.jackfruit.scm.database.adapter.ReturnsAdapter;
import com.jackfruit.scm.database.facade.SupplyChainDatabaseFacade;
import com.jackfruit.scm.database.model.InventoryModels.StockAdjustmentRequest;
import com.jackfruit.scm.database.model.ReturnsModels.ProductReturnRequest;
import com.scm.exceptions.*;
import com.scm.exceptions.categories.*;
import exceptions.DatabaseDesignSubsystem;
import exceptions.WarehouseMgmtSubsystem;
import java.util.ArrayList;
import java.util.UUID;
import src.core.*;
import src.patterns.*;

public class RMAGatewayFacade
    implements
        IInputValidationExceptionSource,
        ISystemInfrastructureExceptionSource
{

    private final WarehouseMgmtSubsystem warehouseExceptions =
        WarehouseMgmtSubsystem.INSTANCE;
    private final DatabaseDesignSubsystem dbExceptions =
        DatabaseDesignSubsystem.INSTANCE;

    private final ReturnValidationHandler validationChain;
    private final VocAnalyzer vocAnalyzer = new VocAnalyzer();

    private SupplyChainDatabaseFacade dbFacade;
    private ReturnsAdapter returnsAdapter;
    private InventoryAdapter inventoryAdapter;
    private SCMExceptionHandler scmHandler;

    public RMAGatewayFacade() {
        this.validationChain = ValidationChainFactory.createDefaultChain();
        try {
            this.dbFacade = new SupplyChainDatabaseFacade();
            this.returnsAdapter = new ReturnsAdapter(dbFacade);
            this.inventoryAdapter = new InventoryAdapter(dbFacade);
        } catch (Exception e) {
            dbExceptions.onDbConnectionFailed("localhost");
        }
    }

    @Override
    public void registerHandler(SCMExceptionHandler h) {
        this.scmHandler = h;
        this.vocAnalyzer.registerHandler(h);
    }

    public void processReturn(
        ReturnRequest request,
        String condition,
        String notes
    ) {
        if (request.basePrice <= 0) {
            fireInvalidReference(
                11,
                "base_price",
                String.valueOf(request.basePrice)
            );
            return;
        }

        try {
            // 1. Inventory Validation (ID 14)
            if (!inventoryAdapter.productExists(request.productId)) {
                warehouseExceptions.onInvalidProductReference(
                    request.productId
                );
                return;
            }

            InspectedItem item = new InspectedItem(
                request,
                condition,
                notes
            );
            ValidationResult val = validationChain.handle(item);

            // 2. Business Rule Exceptions (Warranty & Condition)
            if (!val.approved) {
                if (val.message.contains("Warranty")) {
                    warehouseExceptions.onWarrantyExpired(
                        request.orderId,
                        "Check Original Order"
                    );
                    return;
                }
                if (val.message.contains("condition")) {
                    warehouseExceptions.onReturnConditionInvalid(
                        request.orderId,
                        condition
                    );
                    return;
                }
            }

            String status = val.approved ? "APPROVED" : "REJECTED";
            VocData voc = val.approved
                ? vocAnalyzer.processComment(request.customerComment)
                : new VocData("Neutral", new ArrayList<>());

            // 3. Database Persistence via Module
            String returnId =
                "RET-" + UUID.randomUUID().toString().substring(0, 8);
            returnsAdapter.createReturnRequest(
                new ProductReturnRequest(
                    returnId,
                    request.orderId,
                    "CUST-01",
                    request.productId,
                    notes,
                    request.customerComment,
                    status
                )
            );

            // 4. Inventory Restock Integration
            if (val.approved) {
                inventoryAdapter.createStockAdjustment(
                    new StockAdjustmentRequest(
                        "ADJ-" +
                            UUID.randomUUID().toString().substring(0, 8),
                        request.productId,
                        "INCREASE",
                        1,
                        "Restock: " + returnId,
                        "SYSTEM"
                    )
                );
            }
        } catch (Exception e) {
            dbExceptions.onDbConnectionFailed("localhost");
        }
    }

    @Override
    public void fireInvalidReference(int id, String t, String v) {
        if (scmHandler != null) scmHandler.handle(
            new SCMExceptionEvent(
                id,
                "INVALID_REFUND_AMOUNT",
                Severity.MAJOR,
                "Returns",
                "Refund error",
                t + "=" + v
            )
        );
    }

    @Override
    public void firePlatformFailure(int id, String c, String o, String d) {
        if (scmHandler != null) scmHandler.handle(
            new SCMExceptionEvent(
                id,
                "DB_CONNECTION_FAILED",
                Severity.MAJOR,
                "Returns",
                "Infrastructure error",
                d
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
