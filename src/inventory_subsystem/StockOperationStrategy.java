package inventory_subsystem;

public interface StockOperationStrategy {

    void execute(String sku,
                 String locationId,
                 String supplierId,
                 int quantity,
                 InventoryDataStore repository,
                 InventoryExceptionSource exceptionSource,
                 IssuingPolicy policy);
}
