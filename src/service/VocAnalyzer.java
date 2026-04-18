package src.service;

// SCM Exception Framework Imports
import com.scm.exceptions.*;
import com.scm.exceptions.categories.IMLAlgorithmicExceptionSource;
import java.io.*;
import java.util.*;
import src.core.VocData;
import src.patterns.VocObserver;

/**
 * ML Component for Sentiment Analysis and Keyword Extraction.
 * Integrated with SCM Exception Framework (Category 10).
 */
public class VocAnalyzer implements IMLAlgorithmicExceptionSource {

    private static final String VOC_SCRIPT = "voc_analyzer.py";
    private final List<VocObserver> observers = new ArrayList<>();
    private SCMExceptionHandler scmHandler; // Mandated by SCM framework

    /**
     * Mandated by SCM framework to inject the central handler at startup.
     */
    @Override
    public void registerHandler(SCMExceptionHandler h) {
        this.scmHandler = h;
    }

    public void addObserver(VocObserver obs) {
        observers.add(obs);
    }

    /**
     * Orchestrates the NLP analysis.
     * If Python execution fails, it fires a Category 10 exception and returns fallback data.
     */
    public VocData processComment(String comment) {
        VocData data;
        try {
            data = runPythonAnalyzer(comment);
        } catch (Exception e) {
            // Category 10: ML Model Failure (ID 451)
            fireModelFailure(
                451,
                "SentimentAnalysisModel",
                e.getMessage()
            );

            // Fallback logic: Never halt the system for ML errors
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
        try (
            BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), "UTF-8")
            )
        ) {
            writer.write(comment != null ? comment : "");
        }

        // Read results from STDOUT
        try (
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), "UTF-8")
            )
        ) {
            String sentiment = reader.readLine();
            String kwLine = reader.readLine();

            if (process.waitFor() != 0 || sentiment == null) {
                throw new Exception(
                    "Python script execution failed or returned empty output."
                );
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

    // --- SCM INTERFACE IMPLEMENTATIONS (Category 10) ---

    @Override
    public void fireModelFailure(
        int exceptionId,
        String modelName,
        String reason
    ) {
        if (scmHandler == null) return; // Standard framework safeguard

        // Create the standard SCM event
        scmHandler.handle(
            new SCMExceptionEvent(
                exceptionId,
                "FORECAST_MODEL_FAILURE", // Official SCM Registry name
                Severity.MAJOR,
                "Product Returns",
                "Forecast model failed to execute.",
                "Model: " + modelName + " | Reason: " + reason // Runtime detail
            )
        );
    }

    // Required mandated methods for IMLAlgorithmicExceptionSource
    @Override
    public void fireModelDegradation(
        int id,
        String name,
        String metric,
        double threshold,
        double actual
    ) {}

    @Override
    public void fireMissingInputData(
        int id,
        String name,
        String type,
        String period
    ) {}

    @Override
    public void fireAlgorithmicAlert(
        int id,
        String name,
        String entityId,
        String detail
    ) {}
}
