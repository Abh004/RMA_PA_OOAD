package inventory_subsystem;

import com.jackfruit.scm.database.facade.SupplyChainDatabaseFacade;
import com.jackfruit.scm.database.adapter.InventoryAdapter;
import com.jackfruit.scm.database.model.InventoryModels.StockLevel;
import com.jackfruit.scm.database.model.InventoryModels.StockAdjustment;
import com.jackfruit.scm.database.model.InventoryModels.Product;

import java.time.LocalDateTime;
import java.util.*;

public class InventoryRepository implements InventoryDataStore {

    private final SupplyChainDatabaseFacade facade;
    private final InventoryAdapter adapter;
    private final InventoryExceptionSource exceptionSource;

    private final Map<String, InventoryItem> localStore = new HashMap<>();
    private final List<StockTransaction> transactions = new ArrayList<>();
    private final Map<String, Supplier> suppliers = new HashMap<>();

    public InventoryRepository(InventoryExceptionSource exceptionSource) {
        this.facade = new SupplyChainDatabaseFacade();
        this.adapter = new InventoryAdapter(facade);
        this.exceptionSource = exceptionSource;
    }

    private String key(String sku, String locationId, String supplierId) {
        return sku + "_" + locationId + "_" + supplierId;
    }

    @Override
    public InventoryItem find(String sku, String locationId, String supplierId) {
        return localStore.get(key(sku, locationId, supplierId));
    }

    @Override
    public void save(InventoryItem item) {

        localStore.put(key(item.getSku(), item.getLocationId(), item.getSupplierId()), item);

        Product product = adapter.listProducts()
                .stream()
                .filter(p -> p.sku().equals(item.getSku()))
                .findFirst()
                .orElse(null);

        if (product == null) {
            exceptionSource.fireResourceNotFound(
                    166, "Product", item.getSku()
            );
            return;
        }

        int totalQty = localStore.values().stream()
                .filter(i -> i.getSku().equals(item.getSku()))
                .mapToInt(InventoryItem::getTotalQuantity)
                .sum();

        StockLevel existing = adapter.listStockLevels()
                .stream()
                .filter(s -> s.productId().equals(product.productId()))
                .findFirst()
                .orElse(null);

        if (existing == null) {
            // First time this SKU appears — create the stock level record.
            adapter.createStockLevel(new StockLevel(
                    UUID.randomUUID().toString(),
                    product.productId(),
                    totalQty,
                    0,
                    totalQty,
                    0,
                    0,
                    0,
                    null,
                    "NORMAL",
                    null,
                    LocalDateTime.now()
            ));
        } else {
            // Stock level already exists — record the adjustment.
            // InventoryAdapter has no updateStockLevel(); use createStockAdjustment instead.
            int delta = totalQty - existing.currentStockQty();
            adapter.createStockAdjustment(new StockAdjustment(
                    UUID.randomUUID().toString(),
                    product.productId(),
                    null,
                    delta >= 0 ? "INCREASE" : "DECREASE",
                    Math.abs(delta),
                    "Stock sync from InventoryService",
                    "SYSTEM",
                    LocalDateTime.now(),
                    item.getSku(),
                    "SYSTEM",
                    "Inventory save() reconciliation",
                    false,
                    LocalDateTime.now()
            ));
        }
    }

    @Override
    public boolean exists(String sku, String locationId, String supplierId) {
        return localStore.containsKey(key(sku, locationId, supplierId));
    }

    public void recordTransaction(StockTransaction tx) {
        transactions.add(tx);
    }

    public List<StockTransaction> getTransactions() {
        return transactions;
    }

    public void addSupplier(Supplier supplier) {
        suppliers.put(supplier.getSupplierId(), supplier);
    }

    public Supplier getSupplier(String supplierId) {
        return suppliers.get(supplierId);
    }

    public Collection<Supplier> getAllSuppliers() {
        return suppliers.values();
    }
}
