package edu.neu.ccs.pyramid.multilabel_classification;

import edu.neu.ccs.pyramid.classification.naive_bayes.Bernoulli;
import edu.neu.ccs.pyramid.configuration.Config;
import edu.neu.ccs.pyramid.dataset.*;
import edu.neu.ccs.pyramid.util.SetUtil;
import org.apache.commons.math3.distribution.BinomialDistribution;

import java.io.File;
import java.util.Arrays;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class MultiLabelSuggesterTest {
    private static final Config config = new Config("config/local.config");
    private static final String DATASETS = config.getString("input.datasets");
    private static final String TMP = config.getString("output.tmp");
    public static void main(String[] args) throws Exception{
        test6();
    }

    private static void test1()throws Exception{
        MultiLabelClfDataSet dataSet  = TRECFormat.loadMultiLabelClfDataSet(new File(DATASETS, "/spam/trec_data/test.trec"),
                 DataSetType.ML_CLF_DENSE, true);
        MultiLabelSuggester suggester = new MultiLabelSuggester(dataSet,2);
        System.out.println("bmm="+suggester.getBmm());
        System.out.println("new labels = "+suggester.suggestNewOnes(100));
    }

    private static void test2()throws Exception{
        MultiLabelClfDataSet dataSet  = TRECFormat.loadMultiLabelClfDataSet(new File(DATASETS, "/spam/trec_data/test.trec"),
                DataSetType.ML_CLF_DENSE, true);
        MultiLabelSuggester suggester = new MultiLabelSuggester(dataSet,1);
        System.out.println("bmm="+suggester.getBmm());
        System.out.println("new labels = "+suggester.suggestNewOnes(100));
    }

    private static void test3()throws Exception{
        MultiLabelClfDataSet dataSet  = TRECFormat.loadMultiLabelClfDataSet(new File(DATASETS, "/spam/trec_data/test.trec"),
                DataSetType.ML_CLF_DENSE, true);
        MultiLabelSuggester suggester = new MultiLabelSuggester(dataSet,30);
        System.out.println("bmm="+suggester.getBmm());
        System.out.println("new labels = "+suggester.suggestNewOnes(100));
    }

    private static void test4()throws Exception{
        MultiLabelClfDataSet dataSet = TRECFormat.loadMultiLabelClfDataSet(new File(DATASETS, "ohsumed/3/train.trec"), DataSetType.ML_CLF_SPARSE, true);
        MultiLabelClfDataSet testSet = TRECFormat.loadMultiLabelClfDataSet(new File(DATASETS, "ohsumed/3/test.trec"), DataSetType.ML_CLF_SPARSE, true);
        MultiLabelSuggester suggester = new MultiLabelSuggester(dataSet,10);
        System.out.println("bmm="+suggester.getBmm());


        Set<MultiLabel> trainLabels = Arrays.stream(dataSet.getMultiLabels()).collect(Collectors.toSet());
        Set<MultiLabel> testLabels = Arrays.stream(testSet.getMultiLabels()).collect(Collectors.toSet());
        Set<MultiLabel> newintest = SetUtil.complement(testLabels,trainLabels);
        System.out.println("new labels in test set = "+newintest);

        Set<MultiLabel> sampled = suggester.suggestNewOnes(1000);
        System.out.println("sampled:");
        for (MultiLabel multiLabel: sampled){
            System.out.println(multiLabel+"\t"+newintest.contains(multiLabel));
        }


    }

    private static void test5(){
        MultiLabelClfDataSet dataSet = MLClfDataSetBuilder.getBuilder()
                .numDataPoints(100000).numFeatures(1)
                .numClasses(5).build();
        BinomialDistribution binomialDistribution  = new BinomialDistribution(1,0.25);
        for (int i=0;i<dataSet.getNumDataPoints();i++){
            for (int k=0;k<5;k++){

                double sample = binomialDistribution.sample();
                if (sample==1){
                    dataSet.addLabel(i, k);
                }
            }
        }
        MultiLabelSuggester suggester = new MultiLabelSuggester(dataSet,32);
        System.out.println(suggester.getBmm());

    }

    private static void test6(){
        MultiLabelClfDataSet dataSet = MLClfDataSetBuilder.getBuilder()
                .numDataPoints(40).numFeatures(1)
                .numClasses(3).build();

        for (int i=0;i<10;i++){
            dataSet.addLabel(i,0);
            dataSet.addLabel(i,1);
        }
        for (int i=10;i<20;i++){
            dataSet.addLabel(i,0);
            dataSet.addLabel(i,2);
        }
        for (int i=20;i<30;i++){
            dataSet.addLabel(i,1);
            dataSet.addLabel(i,2);
        }
        MultiLabelSuggester suggester = new MultiLabelSuggester(dataSet,4);
        System.out.println(suggester.getBmm());

    }


}