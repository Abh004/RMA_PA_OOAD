package inventory_subsystem;

public interface InventoryUI {

    void addStock(String sku, String locationId, String supplierId, int quantity);

    void removeStock(String sku, String locationId, String supplierId, int quantity);

    void transferStock(String sku, String fromLocation,
                       String toLocation, String supplierId, int quantity);

    int getStock(String sku, String locationId, String supplierId);
}
