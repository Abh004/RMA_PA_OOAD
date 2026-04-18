package src.core;

/** Represents the product after physical inspection */
public class InspectedItem {

    public final ReturnRequest request;
    public final String conditionGrade; // Sealed, Open, Damaged, Faulty
    public final String inspectionNotes;

    public InspectedItem(ReturnRequest req, String grade, String notes) {
        this.request = req;
        this.conditionGrade = grade;
        this.inspectionNotes = notes;
    }
}
