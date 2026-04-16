package src.service;

import java.io.*;
import java.util.*;
import src.core.VocData;
import src.patterns.VocObserver;

/**
 * Observer Pattern: Notifies downstream systems of analyzed customer feedback.
 */
public class VocAnalyzer {

    private static final String VOC_SCRIPT = "voc_analyzer.py";
    private final List<VocObserver> observers = new ArrayList<>();
    private final ReturnDAO dao = new ReturnDAO();

    public void addObserver(VocObserver obs) {
        observers.add(obs);
    }

    public VocData processComment(String comment) {
        VocData data = runPythonAnalyzer(comment);
        notifyObservers(data);
        return data;
    }

    private VocData runPythonAnalyzer(String comment) {
        try {
            ProcessBuilder pb = new ProcessBuilder("python3", VOC_SCRIPT);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (
                BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream())
                )
            ) {
                writer.write(comment != null ? comment : "");
                writer.newLine();
                writer.flush();
            }

            String sentiment;
            String kwLine;
            try (
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
                )
            ) {
                sentiment = reader.readLine();
                kwLine = reader.readLine();
            }

            process.waitFor();

            if (sentiment == null || sentiment.isBlank()) {
                sentiment = "Neutral";
            }

            List<String> keywords = new ArrayList<>();
            if (kwLine != null && !kwLine.isBlank()) {
                for (String k : kwLine.split(",")) {
                    String t = k.trim();
                    if (!t.isEmpty()) keywords.add(t);
                }
            }

            return new VocData(sentiment, keywords);
        } catch (Exception e) {
            // Log to SCM Schema subsystem_exceptions table
            dao.logException(
                "PRODUCT_ADVANCEMENT",
                "MEDIUM",
                "VoC NLP Failed: " + e.getMessage(),
                e.toString()
            );
            return new VocData("Neutral", new ArrayList<>());
        }
    }

    private void notifyObservers(VocData data) {
        for (VocObserver obs : observers) obs.update(data);
    }
}
