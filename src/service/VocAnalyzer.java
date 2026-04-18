package src.service;

import com.scm.exceptions.*;
import com.scm.exceptions.categories.IMLAlgorithmicExceptionSource;
import exceptions.WarehouseMgmtSubsystem; // Used as the category for model errors
import java.io.*;
import java.util.*;
import src.core.VocData;
import src.patterns.VocObserver;

public class VocAnalyzer implements IMLAlgorithmicExceptionSource {

    private final WarehouseMgmtSubsystem exceptions =
        WarehouseMgmtSubsystem.INSTANCE;
    private static final String VOC_SCRIPT = "voc_analyzer.py";
    private final List<VocObserver> observers = new ArrayList<>();
    private SCMExceptionHandler scmHandler;

    @Override
    public void registerHandler(SCMExceptionHandler h) {
        this.scmHandler = h;
    }

    public void addObserver(VocObserver obs) {
        observers.add(obs);
    }

    public VocData processComment(String comment) {
        try {
            return runPythonAnalyzer(comment);
        } catch (Exception e) {
            // ID 451: FORECAST_MODEL_FAILURE
            // Note: fireModelFailure is used for the registered popup
            fireModelFailure(
                451,
                "SentimentAnalysisModel",
                e.getMessage()
            );
            return new VocData("Neutral", new ArrayList<>());
        }
    }

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
            if (
                process.waitFor() != 0 || sentiment == null
            ) throw new Exception("Script failure");

            List<String> keywords = new ArrayList<>();
            if (kwLine != null && !kwLine.isEmpty()) {
                for (String kw : kwLine.split(","))
                    keywords.add(kw.trim());
            }
            return new VocData(sentiment.trim(), keywords);
        }
    }

    private void notifyObservers(VocData data) {
        for (VocObserver obs : observers) obs.update(data);
    }

    @Override
    public void fireModelFailure(
        int exceptionId,
        String modelName,
        String reason
    ) {
        if (scmHandler == null) return;
        scmHandler.handle(
            new SCMExceptionEvent(
                exceptionId,
                "FORECAST_MODEL_FAILURE",
                Severity.MAJOR,
                "Product Returns",
                "ML model failure.",
                "Model: " + modelName
            )
        );
    }

    @Override
    public void fireModelDegradation(
        int id,
        String n,
        String m,
        double t,
        double a
    ) {}

    @Override
    public void fireMissingInputData(
        int id,
        String n,
        String t,
        String p
    ) {}

    @Override
    public void fireAlgorithmicAlert(
        int id,
        String n,
        String e,
        String d
    ) {}
}
