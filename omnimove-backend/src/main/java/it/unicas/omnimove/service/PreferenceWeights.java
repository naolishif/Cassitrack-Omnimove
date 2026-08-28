package it.unicas.omnimove.service;

import it.unicas.omnimove.model.UserPreferences;

/**
 * Turns the onboarding answers into the four weights the Custom ranking uses.
 *
 * WHY THIS EXISTS RATHER THAN STORED WEIGHTS
 * The database keeps the raw 0..5 answers. Deriving the weights here means the
 * rule lives in one place: change it and every profile follows, with no
 * migration rewriting numbers nobody can check by eye.
 *
 * THE RULE
 * Q1..Q3 (time, cost, environment) are normalised so the three sum to 1, as
 * specified. Reliability is not a fourth answer of the same kind — Q4 asks
 * which way the traveller leans, not how much they care — so it enters as a
 * risk-aversion factor: answering 0 ("I want wide margins") makes transfer
 * slack matter as much as the strongest of the other three, answering 5 ("tight
 * connections are fine") drops it to nothing. The four are then renormalised
 * together, so a Custom score stays inside [0,1] and remains comparable with
 * the others of the same search.
 *
 * All zeros is a real answer and cannot divide: it means "nothing matters
 * particularly", which is an even split.
 */
public record PreferenceWeights(double time, double cost, double eco, double reliability) {

    private static final int SCALE_MAX = 5;

    /** Even split, for an account with no profile yet. */
    public static PreferenceWeights neutral() {
        return new PreferenceWeights(0.25, 0.25, 0.25, 0.25);
    }

    public static PreferenceWeights from(UserPreferences p) {
        if (p == null) return neutral();

        double t = clamp(p.getAnswerTime());
        double c = clamp(p.getAnswerCost());
        double e = clamp(p.getAnswerEco());

        double sum = t + c + e;
        if (sum <= 0) { t = 1; c = 1; e = 1; sum = 3; }   // "nothing in particular"

        t /= sum; c /= sum; e /= sum;

        // Q4 inverted: a low answer means margins matter, a high one means they do not
        double risk = clamp(p.getAnswerReliability()) / (double) SCALE_MAX;
        double r = (1 - risk) * Math.max(Math.max(t, c), e);

        double total = t + c + e + r;
        return new PreferenceWeights(round(t / total), round(c / total),
                                     round(e / total), round(r / total));
    }

    private static double clamp(Integer v) {
        if (v == null) return 3;
        return Math.max(0, Math.min(SCALE_MAX, v));
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
