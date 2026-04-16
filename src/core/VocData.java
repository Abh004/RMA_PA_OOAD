package src.core;

import java.util.*;

public class VocData {

    public final String sentiment;
    public final List<String> keywords;

    public VocData(String sentiment, List<String> keywords) {
        this.sentiment = (sentiment != null) ? sentiment : "Neutral";
        this.keywords = (keywords != null) ? keywords : Collections.emptyList();
    }
}
