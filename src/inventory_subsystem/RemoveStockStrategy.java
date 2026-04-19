package inventory_subsystem;

import java.util.*;

public class RemoveStockStrategy implements StockOperationStrategy {

    @Override
    public void execute(String sku, String locationId, String supplierId, int quantity,
                        InventoryDataStore repository,
                        InventoryExceptionSource exceptionSource,
                        IssuingPolicy policy) {

        InventoryItem item = repository.find(sku, locationId, supplierId);

        if (item == null) {
            exceptionSource.fireResourceNotFound(
                    166, "InventoryItem", sku + "_" + locationId + "_" + supplierId);
            return;
        }

        item.getBatches().removeIf(
                b -> b.getExpiryTime() != null &&
                        b.getExpiryTime().isBefore(java.time.LocalDateTime.now())
        );

        if (item.getTotalQuantity() < quantity) {
            exceptionSource.fireResourceExhausted(
                    167, "Stock",
                    sku + "_" + locationId + "_" + supplierId,
                    quantity, item.getTotalQuantity());
            return;
        }

        if (policy == IssuingPolicy.FEFO) {
            item.getBatches().sort(
                    Comparator.comparing(
                            b -> b.getExpiryTime() != null
                                    ? b.getExpiryTime()
                                    : b.getArrivalTime()
                    )
            );
        } else {
            item.getBatches().sort(
                    Comparator.comparing(InventoryBatch::getArrivalTime)
            );
        }

        int remaining = quantity;
        Iterator<InventoryBatch> iterator = item.getBatches().iterator();

        while (iterator.hasNext() && remaining > 0) {

            InventoryBatch batch = iterator.next();

            int deduct = Math.min(batch.getQuantity(), remaining);

            batch.setQuantity(batch.getQuantity() - deduct);
            remaining -= deduct;

            if (batch.getQuantity() == 0) iterator.remove();
        }

        item.setVersion(item.getVersion() + 1);

        if (repository instanceof InventoryRepository repo) {
            repo.recordTransaction(
                new StockTransaction(sku, locationId, supplierId,
                        -quantity, "REMOVE")
            );
        }

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

        repository.save(item);
    }
}
