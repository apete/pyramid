package edu.neu.ccs.pyramid.multilabel_classification.bmm_variant;

import edu.neu.ccs.pyramid.classification.logistic_regression.LogisticRegression;
import edu.neu.ccs.pyramid.configuration.Config;
import edu.neu.ccs.pyramid.dataset.LabelTranslator;
import edu.neu.ccs.pyramid.dataset.MultiLabel;
import edu.neu.ccs.pyramid.dataset.MultiLabelClfDataSet;
import edu.neu.ccs.pyramid.feature.FeatureList;
import edu.neu.ccs.pyramid.multilabel_classification.MultiLabelClassifier;
import edu.neu.ccs.pyramid.util.BernoulliDistribution;
import edu.neu.ccs.pyramid.util.MathUtil;
import org.apache.commons.math3.distribution.EnumeratedIntegerDistribution;
import org.apache.commons.math3.distribution.UniformRealDistribution;
import org.apache.mahout.math.DenseVector;
import org.apache.mahout.math.RandomAccessSparseVector;
import org.apache.mahout.math.Vector;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Created by Rainicy on 10/23/15.
 */
public class BMMClassifier implements MultiLabelClassifier, Serializable {
    private static final long serialVersionUID = 1L;
    int numLabels;
    int numClusters;
    int numSample = 100;

    String predictMode;

    // parameters
    // format: [numClusters][numLabels]
    LogisticRegression[][] binaryLogitRegressions;

    LogisticRegression softMaxRegression;


    // for predictions from single cluster sampling
    Set<MultiLabel> samplesForCluster;


    /**
     * Default constructor by given a MultiLabelClfDataSet
     * @param dataSet
     * @param numClusters
     */
    public BMMClassifier(MultiLabelClfDataSet dataSet, int numClusters) {
        this(dataSet.getNumClasses(), numClusters, dataSet.getNumFeatures());
    }

    public BMMClassifier(int numClasses, int numClusters, int numFeatures) {
        this.numLabels = numClasses;
        this.numClusters = numClusters;
        // initialize distributions
        this.binaryLogitRegressions = new LogisticRegression[numClusters][numClasses];
        for (int k=0; k<numClusters; k++) {
            for (int l=0; l<numClasses; l++) {
                this.binaryLogitRegressions[k][l] = new LogisticRegression(2,numFeatures);
            }
        }
        this.softMaxRegression = new LogisticRegression(numClusters, numFeatures,true);
        this.samplesForCluster = null;
        this.predictMode = "mixtureMax";
    }

    public BMMClassifier() {
    }

    @Override
    public int getNumClasses() {
        return this.numLabels;
    }

    /**
     * return the log[p(y_n | z_n=k, x_n; w_k)] by all k from 1 to K.
     * @param X
     * @param Y
     * @return
     */
    public double[] clusterConditionalLogProbArr(Vector X, Vector Y) {
        double[] probArr = new double[numClusters];

        for (int k=0; k<numClusters; k++) {
            probArr[k] = clusterConditionalLogProb(X, Y, k);
        }

        return probArr;
    }

    /**
     * return one value for log [p(y_n | z_n=k, x_n; w_k)] by given k;
     * @param X
     * @param Y
     * @param k
     * @return
     */
    private double clusterConditionalLogProb(Vector X, Vector Y, int k) {
        LogisticRegression[] logisticRegressionsK = binaryLogitRegressions[k];

        double logProbResult = 0.0;
        for (int l=0; l<logisticRegressionsK.length; l++) {
            double[] logProbs = logisticRegressionsK[l].predictClassLogProbs(X);
            if (Y.get(l) == 1.0) {
                logProbResult += logProbs[1];
            } else {
                logProbResult += logProbs[0];
            }
        }
        return logProbResult;
    }


    /**
     * return the log[p(y_n | z_n=k, x_n; w_k)] by all k from 1 to K.
     * @param logProbsForX
     * @param Y
     * @return
     */
    public double[] clusterConditionalLogProbArr(double[][][] logProbsForX, Vector Y) {
        double[] probArr = new double[numClusters];

        for (int k=0; k<numClusters; k++) {
            probArr[k] = clusterConditionalLogProb(logProbsForX, Y, k);
        }

        return probArr;
    }

    /**
     * return one value for log [p(y_n | z_n=k, x_n; w_k)] by given k;
     * @param logProbsForX
     * @param Y
     * @param k
     * @return
     */
    private double clusterConditionalLogProb(double[][][] logProbsForX, Vector Y, int k) {
        LogisticRegression[] logisticRegressionsK = binaryLogitRegressions[k];

        double logProbResult = 0.0;
        for (int l=0; l<logisticRegressionsK.length; l++) {
            if (Y.get(l) == 1.0) {
                logProbResult += logProbsForX[k][l][1];
            } else {
                logProbResult += logProbsForX[k][l][0];
            }
        }
        return logProbResult;
    }



    public MultiLabel predict(Vector vector) {
        MultiLabel predLabel = new MultiLabel();
        double maxLogProb = Double.NEGATIVE_INFINITY;
        Vector predVector = new DenseVector(numLabels);

        int[] clusters = IntStream.range(0, numClusters).toArray();
        double[] logisticLogProb = softMaxRegression.predictClassLogProbs(vector);
        double[] logisticProb = softMaxRegression.predictClassProbs(vector);
        EnumeratedIntegerDistribution enumeratedIntegerDistribution = new EnumeratedIntegerDistribution(clusters,logisticProb);

        // cache the prediction for binaryLogitRegressions[numClusters][numLabels]
        double[][][] logProbsForX = new double[numClusters][numLabels][2];
        for (int k=0; k<logProbsForX.length; k++) {
            for (int l=0; l<logProbsForX[k].length; l++) {
                logProbsForX[k][l] = binaryLogitRegressions[k][l].predictClassLogProbs(vector);
            }
        }

        // samples methods
        if (predictMode.equals("mixtureMax")) {
            for (int s=0; s<numSample; s++) {
                int cluster = enumeratedIntegerDistribution.sample();
                Vector candidateY = new DenseVector(numLabels);

                for (int l=0; l<numLabels; l++) {
                    LogisticRegression regression = binaryLogitRegressions[cluster][l];
                    double prob = regression.predictClassProb(vector, 1);
                    BernoulliDistribution bernoulliDistribution = new BernoulliDistribution(prob);
                    candidateY.set(l, bernoulliDistribution.sample());
                }
                // will not consider empty prediction
                if (candidateY.maxValue() == 0) {
                    continue;
                }

                double logProb = logProbYnGivenXnLogisticProb(logisticLogProb, candidateY, logProbsForX);

                if (logProb >= maxLogProb) {
                    predVector = candidateY;
                    maxLogProb = logProb;
                }
            }
        } else if (predictMode.equals("singleTop")) {
            try {
                this.samplesForCluster = sampleFromSingles(vector, logisticProb, 0);
            } catch (IOException e) {
                e.printStackTrace();
            }
            for (MultiLabel label : this.samplesForCluster) {
                // will not consider the empty prediction
                if (label.getMatchedLabels().size() == 0) {
                    continue;
                }
                Vector candidateY = new DenseVector(numLabels);
                for(int labelIndex : label.getMatchedLabels()) {
                    candidateY.set(labelIndex, 1.0);
                }

                double logProb = logProbYnGivenXnLogisticProb(logisticLogProb, candidateY, logProbsForX);

                if (logProb >= maxLogProb) {
                    predVector = candidateY;
                    maxLogProb = logProb;
                }
            }
        } else {
            throw new RuntimeException("Unknown predictMode: " + predictMode);
        }

        for (int l=0; l<numLabels; l++) {
            if (predVector.get(l) == 1.0) {
                predLabel.addLabel(l);
            }
        }
        return predLabel;
    }

    private Set<MultiLabel> sampleFromSingles(Vector vector, double[] logisticProb, double topM) throws IOException {
        int top = 20;
        Set<MultiLabel> samples = new HashSet<>();
        for (int k=0; k<binaryLogitRegressions.length; k++) {
            Set<MultiLabel> sample = sampleFromSingle(vector, top, k, logisticProb[k], topM);
            for (MultiLabel multiLabel : sample) {
                if (!samples.contains(multiLabel)) {
                    samples.add(multiLabel);
                }
            }
        }
        return samples;
    }

    private double getTopM(Vector vector, double[] logisticProb, int m) throws IOException {
        Map<Integer, Double> map = new HashMap<>();
        for (int k=0; k<numClusters; k++) {
            double maxProb = 1.0;
            for (int l=0; l<numLabels; l++) {
                LogisticRegression logisticRegression = binaryLogitRegressions[k][l];
                double prob = logisticRegression.predictClassProbs(vector)[1];
                if (prob > 0.5) {
                    maxProb *= prob;
                } else {
                    maxProb *= (1-prob);
                }
            }
            maxProb *= logisticProb[k];
            map.put(k, maxProb);
        }

        MyComparator comp=new MyComparator(map);
        Map<Integer,Double> sortedMap = new TreeMap(comp);
        sortedMap.putAll(map);

        int count=1;
        double result = 0.0;
        for (Map.Entry<Integer, Double> entry : sortedMap.entrySet()) {
            if (count == m) {
                result = entry.getValue();
            }
            count++;
        }

        return result;
    }

    private Set<MultiLabel> sampleFromSingle(Vector vector, int top, int k, double probK, double topM) throws IOException {
        Set<MultiLabel> sample = new HashSet<>();
        double maxProb = 1.0;

        MultiLabel label = new MultiLabel();
        Map<Integer, Double> labelAbsProbMap = new HashMap<>();
        Map<Integer, Double> labelProbMap = new HashMap<>();
        for (int l=0; l<binaryLogitRegressions[k].length; l++) {
            LogisticRegression logisticRegression = binaryLogitRegressions[k][l];
            double prob = logisticRegression.predictClassProbs(vector)[1];
            if (prob > 0.5) {
                label.addLabel(l);
                maxProb *= prob;
            } else {
                maxProb *= (1-prob);
            }
            double absProb = Math.abs(prob - 0.5);
            labelAbsProbMap.put(l, absProb);
            labelProbMap.put(l, prob);
        }
        MultiLabel copyLabel1 = new MultiLabel();
        for (int l : label.getMatchedLabels()) {
            copyLabel1.addLabel(l);
        }
        sample.add(copyLabel1);

        double prevProb = maxProb;
        for (int i=1; i<top; i++) {
            // find min abs prob among all labels
            int minL = 0;
            double minProb = 100.0;
            for (Map.Entry<Integer, Double> entry : labelAbsProbMap.entrySet()) {
                if (entry.getValue() < minProb) {
                    minL = entry.getKey();
                    minProb = entry.getValue();
                }
            }
            double targetProb = labelProbMap.get(minL);
            // flip the label
            if (label.matchClass(minL)) {
                label.removeLabel(minL);
                prevProb = prevProb / targetProb * (1-targetProb);
            } else {
                label.addLabel(minL);
                prevProb = prevProb * targetProb / (1-targetProb);
            }
            labelAbsProbMap.remove(minL);

            // check if we need to stop sampling.
            if (prevProb < maxProb + 1 - 1.0/probK) {
                break;
            }
            if (prevProb <= topM / numClusters / probK) {
                break;
            }

            MultiLabel copyLabel = new MultiLabel();
            for (int l : label.getMatchedLabels()) {
                copyLabel.addLabel(l);
            }
            sample.add(copyLabel);
        }

        return sample;
    }

    private double logProbYnGivenXnLogisticProb(double[] logisticLogProb, Vector Y, double[][][] logProbsForX) {
        double[] logPYnk = clusterConditionalLogProbArr(logProbsForX,Y);
        double[] sumLog = new double[logisticLogProb.length];
        for (int k=0; k<numClusters; k++) {
            sumLog[k] = logisticLogProb[k] + logPYnk[k];
        }

        return MathUtil.logSumExp(sumLog);
    }


    public String toString() {
        Vector vector = new RandomAccessSparseVector(softMaxRegression.getNumFeatures());
        double[] mixtureCoefficients = softMaxRegression.predictClassProbs(vector);
        final StringBuilder sb = new StringBuilder("BMM{\n");
        sb.append("numLabels=").append(numLabels).append("\n");
        sb.append("numClusters=").append(numClusters).append("\n");
        for (int k=0;k<numClusters;k++){
            sb.append("cluster ").append(k).append(":\n");
            sb.append("proportion = ").append(mixtureCoefficients[k]).append("\n");
        }
        sb.append('}');
        return sb.toString();
    }

    public void setPredictMode(String mode) {
        this.predictMode = mode;
    }


    @Override
    public FeatureList getFeatureList() {
        return null;
    }

    @Override
    public LabelTranslator getLabelTranslator() {
        return null;
    }

    public void setNumSample(int numSample) {
        this.numSample = numSample;
    }

    public static BMMClassifier deserialize(File file) throws Exception {
        try (
                FileInputStream fileInputStream = new FileInputStream(file);
                BufferedInputStream bufferedInputStream = new BufferedInputStream(fileInputStream);
                ObjectInputStream objectInputStream = new ObjectInputStream(bufferedInputStream);
        ){
            BMMClassifier bmmClassifier = (BMMClassifier) objectInputStream.readObject();
            return bmmClassifier;
        }
    }

    public static BMMClassifier deserialize(String file) throws Exception {
        File file1 = new File(file);
        return deserialize(file1);
    }

    @Override
    public void serialize(File file) throws Exception {
        File parent = file.getParentFile();
        if (!parent.exists()) {
            parent.mkdir();
        }
        try (
                FileOutputStream fileOutputStream = new FileOutputStream(file);
                BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(fileOutputStream);
                ObjectOutputStream objectOutputStream = new ObjectOutputStream(bufferedOutputStream);
        ){
            objectOutputStream.writeObject(this);
        }
    }

    @Override
    public void serialize(String file) throws Exception {
        File file1 = new File(file);
        serialize(file1);
    }


    // generate reports:
    public void generateReports(MultiLabelClfDataSet dataSet, String reportsPath, double softmaxVariance, double logitVariance, Config config) throws IOException {


        BMMOptimizer optimizer = new BMMOptimizer(this, dataSet, softmaxVariance, logitVariance);
        optimizer.eStep();
        double[][] gammas = optimizer.gammas;


        File file = new File(reportsPath);
        BufferedWriter bw = new BufferedWriter(new FileWriter(file));

        bw.write("===========================CONFIG=============================\n");
        bw.write(config.toString());
        bw.write("===========================CONFIG=============================\n");
        bw.write("\n\n\n");
        for (int n=0; n<dataSet.getNumDataPoints(); n++) {
            bw.write("data point: " + n + "\t" + "true label: " + dataSet.getMultiLabels()[n].toString() + "\n");

            generateReportsForN(dataSet.getRow(n), dataSet.getMultiLabels()[n],gammas[n], bw, config.getInt("topM"));
            bw.write("===============================================================\n");
            bw.write("\n");
            bw.write("===============================================================\n");
        }

        bw.close();
    }

    private void generateReportsForN(Vector vector, MultiLabel multiLabel, double[] gamma, BufferedWriter bw, int top) throws IOException {
        double[] logisticProb = softMaxRegression.predictClassProbs(vector);
        bw.write("PIs: \t");
        for (double piK : logisticProb) {
            bw.write( String.format( "%.4f", piK) + "\t");
        }
        bw.write("\n");
        bw.write("Gams: \t");
        for (double gams : gamma) {
            bw.write( String.format( "%.4f", gams) + "\t");
        }
        bw.write("\n");

        // cache the prediction for binaryLogitRegressions[numClusters][numLabels]
        double[][][] logProbsForX = new double[numClusters][numLabels][2];
        for (int k=0; k<logProbsForX.length; k++) {
            for (int l=0; l<logProbsForX[k].length; l++) {
                logProbsForX[k][l] = binaryLogitRegressions[k][l].predictClassLogProbs(vector);
            }
        }

        double[] logisticLogProb = softMaxRegression.predictClassLogProbs(vector);
        double topM;
        if (top >= numClusters) {
            topM = 0.0;
        } else {
            topM = getTopM(vector,logisticProb, top);
        }

        this.samplesForCluster = sampleFromSingles(vector, logisticProb, topM);

        Map<MultiLabel, Double> mapMixValue = new HashMap<>();
        Map<MultiLabel, String> mapString = new HashMap<>();
        for (MultiLabel label : this.samplesForCluster) {
            Vector candidateY = new DenseVector(numLabels);
            for(int labelIndex : label.getMatchedLabels()) {
                candidateY.set(labelIndex, 1.0);
            }

            double[] logPYnk = clusterConditionalLogProbArr(logProbsForX,candidateY);
            double[] sumLog = new double[logisticLogProb.length];
            for (int k=0; k<numClusters; k++) {
                sumLog[k] = logisticLogProb[k] + logPYnk[k];
            }
            double logProb = MathUtil.logSumExp(sumLog);

            String eachLine = label.toString() + "\t";
            for (int k=0; k<numClusters; k++) {
                eachLine +=  String.format( "%.4f", Math.exp(logPYnk[k])) + "\t";
            }
            eachLine +=  String.format( "%.4f", Math.exp(logProb)) + "\n";

            mapString.put(label, eachLine);
            mapMixValue.put(label, logProb);
        }
        MyComparator comp=new MyComparator(mapMixValue);
        Map<MultiLabel,Double> sortedMap = new TreeMap(comp);
        sortedMap.putAll(mapMixValue);

        for (Map.Entry<MultiLabel, Double> entry : sortedMap.entrySet()) {
            bw.write(mapString.get(entry.getKey()));
        }

        bw.write("------------------------------------\n");
        Set<MultiLabel> trueSamples = new LinkedHashSet<>();
        trueSamples.add(multiLabel);
        for (int l : multiLabel.getMatchedLabels()) {
            MultiLabel label = new MultiLabel();
            label.addLabel(l);
            trueSamples.add(label);
        }
        for (MultiLabel label : trueSamples) {
            Vector candidateY = new DenseVector(numLabels);
            for(int labelIndex : label.getMatchedLabels()) {
                candidateY.set(labelIndex, 1.0);
            }
            double[] logPYnk = clusterConditionalLogProbArr(logProbsForX,candidateY);
            double[] sumLog = new double[logisticLogProb.length];
            for (int k=0; k<numClusters; k++) {
                sumLog[k] = logisticLogProb[k] + logPYnk[k];
            }
            double logProb = MathUtil.logSumExp(sumLog);
            bw.write(label.toString() + "\t");
            for (int k=0; k<numClusters; k++) {
                bw.write(String.format( "%.4f", Math.exp(logPYnk[k])) + "\t");
            }
            bw.write(String.format( "%.4f", Math.exp(logProb)) + "\n");
        }
    }


    static class MyComparator implements Comparator {

        Map map;

        public MyComparator(Map map) {
            this.map = map;
        }

        public int compare(Object o1, Object o2) {

            return ((Double) map.get(o2)).compareTo((Double) map.get(o1));

        }
    }
}