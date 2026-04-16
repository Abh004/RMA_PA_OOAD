package src.service;

import java.util.UUID;
import src.core.*;
import src.core.VocData;
import src.patterns.*;

public class RMAGatewayFacade {

    private static final String DEFAULT_CUSTOMER_ID = "CUST-01";
    private static final String DEFAULT_RETURN_DOCK_BIN = "BIN-RETURN-DOCK";
    private static final String DEFAULT_RETURN_ZONE_BIN = "BIN-RETURNS-ZONE";

    private final ReturnValidationHandler validationChain;
    private final ReturnDAO dao = new ReturnDAO();
    private final VocAnalyzer vocAnalyzer = new VocAnalyzer();

    public RMAGatewayFacade() {
        // Initialize logic chain
        this.validationChain = ValidationChainFactory.createDefaultChain();
    }

    public void processReturn(
        ReturnRequest request,
        String condition,
        String notes
    ) {
        InspectedItem item = new InspectedItem(request, condition, notes);
        ValidationResult val = validationChain.handle(item);

        String status = val.approved ? "APPROVED" : "REJECTED";
        String vocInfo = "None";

        if (val.approved) {
            VocData voc = vocAnalyzer.processComment(request.customerComment);
            vocInfo = "Sentiment: " + voc.sentiment;
        }

        String returnRequestId =
            "RET-" + UUID.randomUUID().toString().substring(0, 8);

        try {
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
            // 2) Warehouse + Inventory integration (only for approved returns)
            if (val.approved) {
                int returnQty = 1; // Default-safe quantity until line-item qty is modeled

                // Inventory subsystem guard
                if (dao.productExists(request.productId)) {
                    // Warehouse subsystem: record inbound returned goods
                    dao.insertWarehouseReturn(
                        returnRequestId,
                        request.productId,
                        returnQty,
                        mapConditionToWarehouseStatus(condition)
                    );

                    // Warehouse subsystem: movement trail for returned stock
                    dao.insertStockMovementForReturn(
                        "MOV-" + UUID.randomUUID().toString().substring(0, 8),
                        DEFAULT_RETURN_DOCK_BIN,
                        DEFAULT_RETURN_ZONE_BIN,
                        request.productId,
                        returnQty
                    );

                    // Inventory subsystem: stock correction entry
                    dao.insertStockAdjustmentForReturn(
                        "ADJ-" + UUID.randomUUID().toString().substring(0, 8),
                        request.productId,
                        returnQty,
                        "SYSTEM",
                        "Approved customer return: " + returnRequestId
                    );
                } else {
                    dao.logException(
                        "RETURNS_ADVANCEMENT",
                        "MEDIUM",
                        "Unknown product_id in return flow: " +
                            request.productId,
                        "Warehouse/Inventory integration skipped."
                    );
                }
            }

            System.out.println("RMA Processed for Order: " + request.orderId);
        } catch (Exception e) {
            dao.logException(
                "RETURNS_ADVANCEMENT",
                "HIGH",
                e.getMessage(),
                e.toString()
            );
        }
    }

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
