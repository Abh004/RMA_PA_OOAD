package src.service;

import com.jackfruit.scm.database.facade.SupplyChainDatabaseFacade;
import java.util.UUID;
import src.core.*;
import src.patterns.*;

public class RMAGatewayFacade {

    private final ReturnValidationHandler validationChain;
    private final VocAnalyzer vocAnalyzer = new VocAnalyzer();

    public RMAGatewayFacade() {
        this.validationChain = ValidationChainFactory.createDefaultChain();
    }

    public void processReturn(ReturnRequest request, String condition, String notes) {

        if (request.basePrice <= 0) {
            logEvent("INVALID_REFUND_AMOUNT", "MAJOR", "base_price=" + request.basePrice);
            return;
        }

        try (SupplyChainDatabaseFacade dbFacade = new SupplyChainDatabaseFacade()) {

            ReturnAdapter dao = new ReturnAdapter(dbFacade);

            InspectedItem item = new InspectedItem(request, condition, notes);
            ValidationResult val = validationChain.handle(item);

            String status  = val.approved ? "APPROVED" : "REJECTED";
            String vocInfo = "None";

            if (val.approved) {
                VocData voc = vocAnalyzer.processComment(request.customerComment);
                vocInfo = "Sentiment: " + voc.sentiment;
            } else {
                if (val.message.contains("warranty")) {
                    logEvent("WARRANTY_EXPIRED", "MINOR", "Policy rejection: " + val.message);
                }
            }

            String returnRequestId = "RET-" + UUID.randomUUID().toString().substring(0, 8);

            if (val.approved) {
                int returnQty = 1;
                if (dao.productExists(request.productId)) {

                    dao.insertWarehouseReturn(
                            returnRequestId,
                            request.productId,
                            returnQty,
                            mapConditionToWarehouseStatus(condition));

                    dao.insertStockAdjustmentForReturn(
                            "ADJ-" + UUID.randomUUID().toString().substring(0, 8),
                            request.productId,
                            returnQty,
                            "SYSTEM",
                            "Approved customer return: " + returnRequestId);

                } else {
                    logEvent("ITEM_NOT_FOUND", "MAJOR",
                            "Product ID not found in inventory: " + request.productId);
                    dao.logException("Product Returns", "MAJOR",
                            "Product not found: " + request.productId, "");
                }
            }

            System.out.println("[RMA] Return " + returnRequestId
                    + " processed -> " + status + " | " + vocInfo);

        } catch (Exception e) {
            String eventName = e.getClass().getSimpleName().contains("SQL")
                    ? "DB_CONNECTION_FAILED" : "UNREGISTERED_EXCEPTION";
            logEvent(eventName, "MAJOR", e.getMessage());
        }
    }

    private void logEvent(String eventName, String severity, String detail) {
        System.err.println("[SCM-EVENT] " + severity + " | " + eventName
                + " | Subsystem: Product Returns | Detail: " + detail);
    }

    private String mapConditionToWarehouseStatus(String condition) {
        if (condition == null) return "PARTIAL";
        String n = condition.trim().toUpperCase();
        if (n.contains("SEALED") || n.contains("GOOD")) return "GOOD";
        if (n.contains("DAMAGED"))  return "DAMAGED";
        if (n.contains("REJECT"))   return "REJECTED";
        return "PARTIAL";
    }
}
