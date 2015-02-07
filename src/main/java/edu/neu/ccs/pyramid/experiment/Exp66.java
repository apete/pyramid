package edu.neu.ccs.pyramid.experiment;

import edu.neu.ccs.pyramid.classification.logistic_regression.LogisticRegression;
import edu.neu.ccs.pyramid.classification.logistic_regression.LogisticRegressionInspector;
import edu.neu.ccs.pyramid.classification.logistic_regression.RidgeLogisticTrainer;
import edu.neu.ccs.pyramid.configuration.Config;
import edu.neu.ccs.pyramid.dataset.ClfDataSet;
import edu.neu.ccs.pyramid.dataset.DataSetType;
import edu.neu.ccs.pyramid.dataset.DataSetUtil;
import edu.neu.ccs.pyramid.dataset.TRECFormat;
import edu.neu.ccs.pyramid.elasticsearch.ESIndex;
import edu.neu.ccs.pyramid.elasticsearch.SingleLabelIndex;
import edu.neu.ccs.pyramid.eval.Accuracy;
import edu.neu.ccs.pyramid.feature.FeatureUtility;
import edu.neu.ccs.pyramid.util.Pair;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Created by chengli on 2/7/15.
 */
public class Exp66 {
    public static void main(String[] args) throws Exception{
        if (args.length !=1){
            throw new IllegalArgumentException("please specify the config file");
        }

        Config config = new Config(args[0]);
        System.out.println(config);
        ESIndex index = loadIndex(config);
        Map<String, Integer> docLengths = getDocLengths(index);
//        System.out.println(docLengths);

        ClfDataSet dataSet = loadGoodDataSet(config);

        List<List<FeatureUtility>> goodNgrams  = getGoodNgrams(config);
        List<List<Integer>> dataPerClass = DataSetUtil.labelToDataPoints(dataSet);

        File output = new File(config.getString("output.folder"));
        output.mkdirs();

        BufferedWriter bw = new BufferedWriter(new FileWriter(new File(config.getString("output.folder"),config.getString("output.docs"))));

        for (int k=0;k<dataSet.getNumClasses();k++){
            System.out.println("for class "+k);
            List<Integer> docForClass = setCover(goodNgrams.get(k),dataPerClass.get(k),dataSet);
            String str = docForClass.toString().replace("[","").replace("]","");
            bw.write(str);
            bw.newLine();
        }

        bw.close();





        index.close();

    }

    static Map<String, Integer> getDocLengths(ESIndex index){
        int numDocsInIndex = index.getNumDocs();
        Map<String, Integer> lengths = new ConcurrentHashMap<>();
        IntStream.range(0,numDocsInIndex).parallel()
                .mapToObj(i -> "" + i)
                .forEach(id -> lengths.put(id, index.getDocLength(id)));
        return lengths;
    }

    static SingleLabelIndex loadIndex(Config config) throws Exception{
        SingleLabelIndex.Builder builder = new SingleLabelIndex.Builder()
                .setIndexName(config.getString("index.indexName"))
                .setClusterName(config.getString("index.clusterName"))
                .setClientType(config.getString("index.clientType"))
                .setLabelField(config.getString("index.labelField"))
                .setExtLabelField(config.getString("index.extLabelField"))
                .setDocumentType(config.getString("index.documentType"));
        if (config.getString("index.clientType").equals("transport")){
            String[] hosts = config.getString("index.hosts").split(Pattern.quote(","));
            String[] ports = config.getString("index.ports").split(Pattern.quote(","));
            builder.addHostsAndPorts(hosts,ports);
        }
        SingleLabelIndex index = builder.build();
        System.out.println("index loaded");
        System.out.println("there are "+index.getNumDocs()+" documents in the index.");
//        for (int i=0;i<index.getNumDocs();i++){
//            System.out.println(i);
//            System.out.println(index.getLabel(""+i));
//        }
        return index;
    }

    public static List<List<FeatureUtility>> getGoodNgrams(Config config) throws Exception{
        File dataFile = new File(config.getString("input.goodDataSet"),"train.trec");
        ClfDataSet dataSet = TRECFormat.loadClfDataSet(dataFile, DataSetType.CLF_SPARSE, true);
        RidgeLogisticTrainer trainer = RidgeLogisticTrainer.getBuilder()
                .setHistory(5)
                .setGaussianPriorVariance(config.getDouble("gaussianPriorVariance"))
                .setEpsilon(0.1)
                .build();


        LogisticRegression logisticRegression = trainer.train(dataSet);
        System.out.println("accuracy on good dataset = "+ Accuracy.accuracy(logisticRegression, dataSet));
        int limit = config.getInt("topFeature.limit");
        List<List<FeatureUtility>> goodFeatures = new ArrayList<>();
        for (int k=0;k<logisticRegression.getNumClasses();k++){
            goodFeatures.add(LogisticRegressionInspector.topFeatures(logisticRegression, k, limit));
        }
        return goodFeatures;
    }

    private static double docUtility(Set<FeatureUtility> remainingFeatures, ClfDataSet dataSet, int dataPoint){
        return remainingFeatures.stream().filter(featureUtility -> dataSet.getRow(dataPoint).get(featureUtility.getIndex())>0)
                .mapToDouble(FeatureUtility::getUtility).sum();
    }

    private static Set<FeatureUtility> matchedFeatures(Set<FeatureUtility> remainingFeatures, ClfDataSet dataSet, int dataPoint){
        return remainingFeatures.stream().filter(featureUtility -> dataSet.getRow(dataPoint).get(featureUtility.getIndex()) > 0)
                .collect(Collectors.toSet());
    }

    private static List<Integer> setCover(List<FeatureUtility> featureUtilities, List<Integer> dataPoints, ClfDataSet dataSet){
        Set<FeatureUtility> remainingFeatures = new HashSet<>(featureUtilities);
        Set<Integer> remainingData = new HashSet<>(dataPoints);
        Comparator<Pair<Integer,Double>> comparator = Comparator.comparing(Pair::getSecond);
        List<Integer> docs = new ArrayList<>();

        for (int iteration =0;iteration<100;iteration++){
            System.out.println("iteration "+iteration);
            Pair<Integer,Double> bestPair = remainingData.stream().parallel().map(dataPoint ->
                    new Pair<>(dataPoint, docUtility(remainingFeatures,dataSet,dataPoint)))
                    .max(comparator).get();
            int bestData = bestPair.getFirst();
            double bestUtility = bestPair.getSecond();
            docs.add(bestData);
            Set<FeatureUtility> matchedFeatures = matchedFeatures(remainingFeatures,dataSet,bestData);
            System.out.println("best document = "+bestData+", extId = "+dataSet.getDataPointSetting(bestData).getExtId());
            System.out.println("utility = "+bestUtility);
            System.out.println("matched features = "+ matchedFeatures);

            remainingFeatures.removeAll(matchedFeatures);
            remainingData.remove(bestData);

        }
        return docs;
    }

    public static ClfDataSet loadGoodDataSet(Config config) throws Exception{
        File dataFile = new File(config.getString("input.goodDataSet"),"train.trec");
        ClfDataSet dataSet = TRECFormat.loadClfDataSet(dataFile, DataSetType.CLF_SPARSE,true);
        return dataSet;
    }


}
