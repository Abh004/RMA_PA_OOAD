package inventory_subsystem;

import java.time.LocalDateTime;
import java.util.*;

public class TransferStockStrategy implements StockOperationStrategy {

    @Override
    public void execute(String sku, String fromLocation, String supplierId, int quantity,
                        InventoryDataStore repository,
                        InventoryExceptionSource exceptionSource,
                        IssuingPolicy policy) {

        InventoryItem source = repository.find(sku, fromLocation, supplierId);

        if (source == null) {
            exceptionSource.fireResourceNotFound(
                    166, "InventoryItem",
                    sku + "_" + fromLocation + "_" + supplierId);
            return;
        }

        if (source.getTotalQuantity() < quantity) {
            exceptionSource.fireResourceExhausted(
                    167, "Stock",
                    sku + "_" + fromLocation + "_" + supplierId,
                    quantity, source.getTotalQuantity());
            return;
        }

        if (policy == IssuingPolicy.FEFO) {
            source.getBatches().sort(
                    Comparator.comparing(
                            b -> b.getExpiryTime() != null
                                    ? b.getExpiryTime()
                                    : b.getArrivalTime()
                    )
            );
        } else {
            source.getBatches().sort(
                    Comparator.comparing(InventoryBatch::getArrivalTime)
            );
        }

        int remaining = quantity;
        Iterator<InventoryBatch> iterator = source.getBatches().iterator();

        while (iterator.hasNext() && remaining > 0) {
            InventoryBatch batch = iterator.next();

            int deduct = Math.min(batch.getQuantity(), remaining);
            batch.setQuantity(batch.getQuantity() - deduct);
            remaining -= deduct;

            if (batch.getQuantity() == 0) iterator.remove();
        }

        source.setVersion(source.getVersion() + 1);

        if (repository instanceof InventoryRepository repo) {
            repo.recordTransaction(
                new StockTransaction(sku, fromLocation, supplierId,
                        -quantity, "TRANSFER_OUT")
            );
        }

        if (source.getTotalQuantity() < source.getReorderThreshold()) {
            exceptionSource.fireResourceExhausted(
                    200,
                    "ReorderTrigger",
                    sku,
                    source.getReorderThreshold(),
                    source.getTotalQuantity()
            );
        }

        if (source.getTotalQuantity() < source.getSafetyStockLevel()) {
            exceptionSource.fireResourceExhausted(
                201,
                "SafetyStockBreach",
                sku,
                source.getSafetyStockLevel(),
                source.getTotalQuantity()
            );
        }

        repository.save(source);
    }

    public void addToDestination(String sku, String toLocation, String supplierId,
                                int quantity, InventoryDataStore repository,
                                InventoryExceptionSource exceptionSource) {

        InventoryItem destination = repository.find(sku, toLocation, supplierId);

        if (destination == null) {
            destination = new InventoryItem(sku, toLocation, supplierId, 0);
        }

        destination.addBatch(new InventoryBatch(
                UUID.randomUUID().toString(),
                quantity,
                LocalDateTime.now(),
                null,
            new ArrayList<>(),
            0.0
        ));

        destination.setVersion(destination.getVersion() + 1);

        if (repository instanceof InventoryRepository repo) {
            repo.recordTransaction(
                new StockTransaction(sku, toLocation, supplierId,
                        quantity, "TRANSFER_IN")
            );
        }

        if (destination.getTotalQuantity() < destination.getReorderThreshold()) {
            exceptionSource.fireResourceExhausted(
                    200,
                    "ReorderTrigger",
                    sku,
                    destination.getReorderThreshold(),
                    destination.getTotalQuantity()
            );
        }

        if (destination.getTotalQuantity() < destination.getSafetyStockLevel()) {
            exceptionSource.fireResourceExhausted(
                    201,
                    "SafetyStockBreach",
                    sku,
                    destination.getSafetyStockLevel(),
                    destination.getTotalQuantity()
            );
        }

        repository.save(destination);
    }
}
