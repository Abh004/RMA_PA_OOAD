package src.patterns;

import src.core.VocData;

public interface VocObserver {
    void update(VocData data);
}

class RndFeedbackApi implements VocObserver {

    @Override
    public void update(VocData data) {
        System.out.println("R&D API notified of sentiment: " + data.sentiment);
    }
}
