package inventory_subsystem;

public interface InventoryDataStore {

    InventoryItem find(String sku, String locationId, String supplierId);

    void save(InventoryItem item);

    boolean exists(String sku, String locationId, String supplierId);
}
