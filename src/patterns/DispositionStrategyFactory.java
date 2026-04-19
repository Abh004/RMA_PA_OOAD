package src.patterns;

import src.core.InspectedItem;

/**
 * Factory that selects the correct DispositionStrategy based on the
 * physical condition grade assigned during inspection.
 *
 * Condition Grade → Strategy mapping:
 *   Sealed   → RestockStrategy  (never opened, goes back to primary stock)
 *   Open     → RestockStrategy  (opened but intact, restocked with restocking fee)
 *   Damaged  → RepairStrategy   (physical damage, route to repair centre)
 *   Faulty   → RepairStrategy   (functional defect, route to repair centre)
 *   Beyond   → ScrapStrategy    (unrepairable, route to e-waste facility)
 *
 * Any unrecognised grade defaults to RepairStrategy for human inspection.
 */
public class DispositionStrategyFactory {

    private DispositionStrategyFactory() {}

    public static DispositionStrategy select(String conditionGrade) {
        if (conditionGrade == null) return new RepairStrategy();
        switch (conditionGrade.trim().toLowerCase()) {
            case "sealed":
            case "open":
                return new RestockStrategy();
            case "damaged":
            case "faulty":
                return new RepairStrategy();
            case "beyond":
            case "scrap":
                return new ScrapStrategy();
            default:
                System.out.println("[Disposition] Unknown grade '" + conditionGrade
                        + "' — defaulting to RepairStrategy for manual inspection.");
                return new RepairStrategy();
        }
    }

    /**
     * Build the refund calculator chain for the given condition grade.
     *
     * Sealed  → full price (no deductions)
     * Open    → -10% restocking fee
     * Damaged → -10% restocking fee + -50% condition deduction
     * Faulty  → -10% restocking fee only (functional fault, not cosmetic)
     * Beyond  → no refund (scrap)
     *
     * @param basePrice     original purchase price
     * @param conditionGrade physical condition grade
     * @return configured RefundCalculator chain
     */
    public static RefundCalculator buildRefundChain(double basePrice, String conditionGrade) {
        RefundCalculator base = new BaseRefund(basePrice);
        if (conditionGrade == null) return base;
        switch (conditionGrade.trim().toLowerCase()) {
            case "sealed":
                return base;                                                    // 100% refund
            case "open":
            case "faulty":
                return new RestockingFeeDecorator(base);                        // 90% refund
            case "damaged":
                return new ConditionDeductionDecorator(
                        new RestockingFeeDecorator(base));                      // 45% refund
            case "beyond":
            case "scrap":
                return new BaseRefund(0.0);                                     //  0% refund
            default:
                return new RestockingFeeDecorator(base);                        // 90% refund default
        }
    }
}
