package com.atguigu.spark.core.transformatioins;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.VoidFunction;

import java.util.Arrays;

public class Subtract {
    public static void main(String[] args) {
        SparkConf conf = new SparkConf();
        conf.setMaster("local");
        conf.setAppName("test");

        JavaSparkContext sc = new JavaSparkContext(conf);
        sc.setLogLevel("Error");

        JavaRDD<Integer> rdd1 = sc.parallelize(Arrays.asList(1, 2, 3, 4, 5));
        JavaRDD<Integer> rdd2 = sc.parallelize(Arrays.asList(1,2,3,6,7));

        JavaRDD<Integer> subtract = rdd2.subtract(rdd1);
        subtract.foreach(new VoidFunction<Integer>() {
            @Override
            public void call(Integer integer) throws Exception {
                System.out.println(integer);
            }
        });


    }
}
