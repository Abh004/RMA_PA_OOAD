package inventory_subsystem;

import java.time.LocalDateTime;
import java.util.*;

public class AddStockStrategy implements StockOperationStrategy {

    @Override
    public void execute(String sku, String locationId, String supplierId, int quantity,
                        InventoryDataStore repository,
                        InventoryExceptionSource exceptionSource,
                        IssuingPolicy policy) {

        if (quantity <= 0) {
            exceptionSource.fireConflict(
                    168, "StockOperation",
                    sku, "Invalid quantity: " + quantity);
            return;
        }

        InventoryItem item = repository.find(sku, locationId, supplierId);

        if (item == null) {
            item = new InventoryItem(sku, locationId, supplierId, quantity);
        } else {
            item.addBatch(new InventoryBatch(
                    UUID.randomUUID().toString(),
                    quantity,
                    LocalDateTime.now(),
                    null,
                    new ArrayList<>(),
                    0.0
            ));
            item.setVersion(item.getVersion() + 1);
        }

        if (repository instanceof InventoryRepository repo) {
            repo.recordTransaction(
                new StockTransaction(sku, locationId, supplierId,
                        quantity, "ADD")
            );
        }

        repository.save(item);

        if (item.getTotalQuantity() < item.getReorderThreshold()) {
            exceptionSource.fireResourceExhausted(
                    200,
                    "ReorderTrigger",
                    sku,
                    item.getReorderThreshold(),
                    item.getTotalQuantity()
            );
        }

        if (item.getTotalQuantity() < item.getSafetyStockLevel()) {
            exceptionSource.fireResourceExhausted(
                    201,
                    "SafetyStockBreach",
                    sku,
                    item.getSafetyStockLevel(),
                    item.getTotalQuantity()
            );
        }
    }
}
