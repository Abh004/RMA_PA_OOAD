package src.patterns;

import src.core.InspectedItem;

/**
 * Strategy Pattern: Defines interchangeable algorithms for item routing.
 */
public interface DispositionStrategy {
    String route(InspectedItem item);
}

class RestockStrategy implements DispositionStrategy {

    @Override
    public String route(InspectedItem item) {
        return "WAREHOUSE_RESTOCK";
    }
}

class RepairStrategy implements DispositionStrategy {

    @Override
    public String route(InspectedItem item) {
        return "TECHNICAL_REPAIR";
    }
}

class ScrapStrategy implements DispositionStrategy {

    @Override
    public String route(InspectedItem item) {
        return "E_WASTE_SCRAP";
    }
}
