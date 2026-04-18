package src.service;

import java.io.*;
import java.util.*;
import src.core.VocData;
import src.patterns.VocObserver;

/**
 * ML Component for Sentiment Analysis and Keyword Extraction.
 *
 * Calls the Python voc_analyzer.py script as a subprocess and parses its
 * two-line output: sentiment on line 1, comma-separated keywords on line 2.
 *
 * NOTE: The com.scm.exceptions framework was not provided as a JAR.
 * ML failure handling is done via console logging. The system never halts
 * on ML errors — it falls back to "Neutral" sentiment.
 */
public class VocAnalyzer {

    private static final String VOC_SCRIPT = "voc_analyzer.py";
    private final List<VocObserver> observers = new ArrayList<>();

    public void addObserver(VocObserver obs) {
        observers.add(obs);
    }

    /**
     * Orchestrates the NLP analysis.
     * On any failure, logs to stderr and returns safe fallback data.
     */
    public VocData processComment(String comment) {
        VocData data;
        try {
            data = runPythonAnalyzer(comment);
        } catch (Exception e) {
            // Log ML failure but never halt the system for it
            System.err.println("[SCM-EVENT] MAJOR | FORECAST_MODEL_FAILURE"
                    + " | Subsystem: Product Returns"
                    + " | Model: SentimentAnalysisModel | Reason: " + e.getMessage());
            data = new VocData("Neutral", new ArrayList<>());
        }

        notifyObservers(data);
        return data;
    }

    private VocData runPythonAnalyzer(String comment) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("python3", VOC_SCRIPT);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // Pass comment via STDIN
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), "UTF-8"))) {
            writer.write(comment != null ? comment : "");
        }

        // Read results from STDOUT
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), "UTF-8"))) {

            String sentiment = reader.readLine();
            String kwLine    = reader.readLine();

            if (process.waitFor() != 0 || sentiment == null) {
                throw new Exception(
                        "Python script execution failed or returned empty output.");
            }

            List<String> keywords = new ArrayList<>();
            if (kwLine != null && !kwLine.isEmpty()) {
                for (String kw : kwLine.split(",")) {
                    keywords.add(kw.trim());
                }
            }
            return new VocData(sentiment.trim(), keywords);
        }
    }

    private void notifyObservers(VocData data) {
        for (VocObserver obs : observers) {
            obs.update(data);
        }
    }
}
