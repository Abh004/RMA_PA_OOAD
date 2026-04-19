package src.service;

import com.scm.subsystems.ProductReturnsSubsystem;
import java.io.*;
import java.util.*;
import src.core.VocData;
import src.patterns.VocObserver;

/**
 * Voice of Customer (VOC) Analyzer
 * Processes customer comments using Python sentiment analysis and notifies observers.
 *
 * Exception handling uses ProductReturnsSubsystem singleton from scm-exception-handler-v3.jar.
 * VOC analysis failure has no registered ID in the master register. Since raise() is private
 * in the JAR, unregistered exceptions are logged to stderr as a fallback.
 */
public class VocAnalyzer {

    private static final String VOC_SCRIPT = "voc_analyzer.py";

    // Step 5: subsystem instance declaration per SCM integration guide
    private final ProductReturnsSubsystem exceptions = ProductReturnsSubsystem.INSTANCE;

    private final List<VocObserver> observers = new ArrayList<>();

    /**
     * Add an observer to be notified of VOC analysis results.
     */
    public void addObserver(VocObserver obs) {
        observers.add(obs);
    }

    /**
     * Process a customer comment and extract sentiment and keywords.
     * Falls back to neutral sentiment if analysis fails.
     */
    public VocData processComment(String comment) {
        try {
            VocData result = runPythonAnalyzer(comment);
            notifyObservers(result);
            return result;
        } catch (Exception e) {
            handleAnalysisException(e, comment);
            return new VocData("Neutral", new ArrayList<>());
        }
    }

    /**
     * Execute the Python VOC analysis script.
     */
    private VocData runPythonAnalyzer(String comment) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("python3", VOC_SCRIPT);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (
            BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), "UTF-8")
            )
        ) {
            writer.write(comment != null ? comment : "");
        }

        try (
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), "UTF-8")
            )
        ) {
            String sentiment = reader.readLine();
            String kwLine = reader.readLine();

            if (process.waitFor() != 0 || sentiment == null) {
                throw new Exception("Python script execution failed");
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

    /**
     * Notify all observers of VOC analysis results.
     */
    private void notifyObservers(VocData data) {
        for (VocObserver obs : observers) {
            try {
                obs.update(data);
            } catch (Exception e) {
                System.err.println("Error notifying observer: " + e.getMessage());
            }
        }
    }

    /**
     * Handle exceptions during VOC analysis.
     * VOC analysis failure has no registered ID in the ProductReturns master register.
     * raise() is private in the JAR — logging to stderr as fallback per Step 7 intent.
     */
    private void handleAnalysisException(Exception e, String comment) {
        System.err.println("[VOC] UNREGISTERED_EXCEPTION: Voice of Customer analysis failed."
                + " Comment: " + (comment != null ? comment : "null")
                + ", Error: " + e.getMessage());
    }
}
