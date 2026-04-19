package inventory_subsystem;

public class Supplier {

    private String supplierId;
    private String name;
    private int leadTimeDays;
    private double performanceRating;

    public Supplier(String supplierId, String name,
                    int leadTimeDays, double performanceRating) {

        this.supplierId = supplierId;
        this.name = name;
        this.leadTimeDays = leadTimeDays;
        this.performanceRating = performanceRating;
    }

    public String getSupplierId() { return supplierId; }
    public String getName() { return name; }
    public int getLeadTimeDays() { return leadTimeDays; }
    public double getPerformanceRating() { return performanceRating; }

    public void setLeadTimeDays(int leadTimeDays) {
        this.leadTimeDays = leadTimeDays;
    }

    public void setPerformanceRating(double performanceRating) {
        this.performanceRating = performanceRating;
    }
}
