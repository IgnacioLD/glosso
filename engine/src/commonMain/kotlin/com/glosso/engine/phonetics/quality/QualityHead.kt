package com.glosso.engine.phonetics.quality

import kotlin.math.exp

/**
 * The frozen shared-inventory quality head: a class-balanced logistic regression
 * that turns the per-phone features of [QualityFeatures] into P(this phone was
 * produced correctly).
 *
 * PROVENANCE (where these numbers come from, so they are not magic constants):
 *   * source: `benchmark/phon-multilingual/finalize.py`, model "logreg", PR #52;
 *   * trained on speechocean762 (CC BY 4.0) expert per-phone labels, split
 *     `l2_calib` (2,500 utterances, 125 Mandarin-L1 speakers); full-batch Adam,
 *     lr=0.05, l2=3e-3, 500 epochs, seed 7, class-balanced weights;
 *   * features (6): within-utterance normalised GOP, GOP rank, margin,
 *     within-utterance normalised entropy, log frames, produced==target;
 *   * plus a one-hot over [PhonemeClass.NAMES] and a one-hot over the 392-token
 *     production vocabulary, plus the bias at the end;
 *   * reproducibility: rebuilding the head from the frozen feature files with
 *     `finalize.fit_logreg` reproduces every coefficient to 0.0 absolute error.
 *
 * The head was chosen for cross-corpus transfer, not for within-corpus fit: the
 * absolute-posterior family inverts on Common Voice (AUC 0.49) while the
 * within-utterance normalised family separates (0.60). The aggregation (mean of
 * the worst-3 phones) is what carries the signal, not the per-phone mean.
 */
internal object QualityHead {

    const val ID = "shared-inventory-logreg-worst3-v1"
    const val VOCAB_SIZE = 392
    const val CONTINUOUS = 6
    const val CLASSES = 8

    private const val BIAS_INDEX = CONTINUOUS + CLASSES + VOCAB_SIZE

    /**
     * @param continuous the six normalised features, in the order above
     * @param phoneClass one of [PhonemeClass.NAMES]
     * @param tokenId resolved production-vocabulary id of the reference phone
     */
    fun probability(continuous: DoubleArray, phoneClass: String, tokenId: Int): Double {
        var z = W[BIAS_INDEX]
        for (i in 0 until CONTINUOUS) z += W[i] * continuous[i]
        val classIndex = PhonemeClass.NAMES.indexOf(phoneClass)
        if (classIndex >= 0) z += W[CONTINUOUS + classIndex]
        if (tokenId in 0 until VOCAB_SIZE) z += W[CONTINUOUS + CLASSES + tokenId]
        return 1.0 / (1.0 + exp(-z.coerceIn(-30.0, 30.0)))
    }

    // [continuous 6][class 8][token 392][bias 1] = 407.
    private val W = doubleArrayOf(
        0.5541114858, -0.4227187196, 0.7450632826, -0.1552694229, -0.6733003679,
        1.2475055553, 0.4516842161, 0.029935795, 0.0725487049, 0.2419146395,
        -0.4380222781, -0.373387031, 0.0, 0.0153259538, 0.0,
        0.0, 0.0, 0.0, -0.3962521398, -0.1015514123,
        -0.6188528311, -0.2658344096, -0.2786313676, 0.0, -0.0171141551,
        0.1414978569, -0.1735331342, -0.0079735901, -0.4224144523, 0.0,
        0.0, -0.2533199028, -0.0743811863, 0.0, 0.0,
        -0.3113917429, 0.0367913656, 0.1724176937, 0.1470331367, 0.0150164225,
        0.3430187515, -0.4824010649, 0.0, 0.0757319365, 0.0,
        0.0, 0.2406122647, -0.1571540607, 0.5695311445, 0.4121863382,
        0.1566164109, 0.2525074873, 0.034381001, 0.3427364226, 0.2578584991,
        0.0, -0.0337965482, 0.0090149978, -0.2299236631, 0.0,
        0.0, 0.0, 0.0, 0.1695282962, 0.0,
        0.0, -0.0941815338, -0.0124689288, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.2623691052, 0.1028844548,
        0.0, 0.0, 0.006310956, 0.0, -0.0216695115,
        0.1390301847, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.065770909,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 1.4683157158,
    )
}
